package com.financegenie.android.recovery

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.csv.*
import com.financegenie.android.data.*
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.storageClock
import com.financegenie.android.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RecoveryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "recovery-storage-tests-only"
    private var store = LocalProfileStore(context, storageClock, namespace)
    @After fun clean() {
        store.close()
        context.databaseList().filter { it.startsWith(namespace) }.forEach { context.deleteDatabase(it) }
        AtomicFile(File(context.filesDir, "$namespace-active-profile")).delete()
        File(context.cacheDir, "$namespace-export.json").delete()
        context.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private suspend fun source(): PortableBackup { seedRecovery(store.session.value.repository); return store.backup() }
    private fun reject(block: suspend () -> Unit) = runBlocking { try { block(); fail("Expected conflict/failure") } catch (_: Exception) {} }

    @Test fun thousandRowsRestoreIntoNewIdentityPreservesEveryRecordAndCsvUndoReplayGuards() = runBlocking {
        val source = source()
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(source, it, storageClock) }.toByteArray()
        val parsed = BackupCodec.read(bytes.inputStream(), storageClock)
        val old = store.session.value
        store.restore(parsed, old.repository.read().settings)
        val restored = store.backup()
        assertNotEquals(source.ledger.settings.localId, restored.ledger.settings.localId)
        assertEquals(source.forNewProfile(restored.ledger.settings.localId).ordered(), restored.copy(createdAtMillis = source.createdAtMillis).ordered())
        assertFalse(context.getDatabasePath(old.databaseName).exists())
        val report = LedgerCalculator(storageClock).monthlyReport(restored.ledger.snapshot, YearMonth.of(2026, 11))
        assertEquals(1_000, restored.ledger.transactions.size)
        assertEquals(325_849L, report.netSpendingMinor)
        assertEquals(mapOf(recoveryBank to 5_174_151L, recoveryCash to 500_000L, recoveryCard to 0L), report.accountPositionsMinor)
        assertEquals(6_000_000L, report.incomeMinor)
        val repo = store.session.value.repository
        val doc = CsvReader.read(recoveryCsv.byteInputStream())
        val request = CsvImportRequest(doc, CsvMapping.forHeaders(doc.headers), recoveryBank, mapOf(2 to CsvDecision(CsvAction.CREATE, categoryId = "food")), repo.read().settings.revision)
        try { repo.commitCsv(request); fail("Restored file must stay replay protected") } catch (_: CsvReplayException) {}
        val batch = restored.ledger.importBatches.single()
        repeat(2) { repo.undoCsv(batch.id, repo.read().settings.revision); repo.restoreCsv(batch.id, repo.read().settings.revision) }
        assertEquals(325_849L, LedgerCalculator(storageClock).monthlyReport(repo.read().snapshot, YearMonth.of(2026, 11)).netSpendingMinor)
        reject { old.repository.saveCategory(LedgerCategory("stale", "Stale")) }
    }

    @Test fun staleRestoreAndDeleteConfirmationsPreserveTheCurrentLedger() = runBlocking {
        val source = source()
        val before = store.session.value
        before.repository.saveCategory(LedgerCategory("new", "New"))
        val latest = before.repository.read()
        reject { store.restore(source, source.ledger.settings) }
        reject { store.deleteAll(source.ledger.settings) }
        assertEquals(before.databaseName, store.session.value.databaseName)
        assertEquals(latest, store.session.value.repository.read())
        assertFalse(File(context.filesDir, "$namespace-active-profile").exists())
    }

    @Test fun failingStagedInsertCannotAlterTheSelectedDatabase() = runBlocking {
        val source = source()
        val before = store.session.value
        store.close()
        store = LocalProfileStore(context, storageClock, namespace) { name ->
            FinanceGenieDatabase.open(context, name, storageClock).also { db ->
                if (name.contains("-profile-")) db.openHelper.writableDatabase.execSQL("CREATE TRIGGER recovery_disk_failure AFTER INSERT ON transactions BEGIN SELECT RAISE(ABORT, 'Synthetic disk failure'); END")
            }
        }
        reject { store.restore(source, source.ledger.settings) }
        assertEquals(before.databaseName, store.session.value.databaseName)
        assertEquals(source.ledger, store.session.value.repository.read())
        assertTrue(context.databaseList().filter { it.startsWith(namespace) }.all { it.startsWith(before.databaseName) })
    }

    @Test fun deletionRemovesOwnedDatabasesAndSidecarsButKeepsExportsAndPrivacy() = runBlocking {
        val source = source()
        val exported = File(context.cacheDir, "$namespace-export.json").apply { writeText("Synthetic user-selected export") }
        val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean("app_lock", true).commit())
        val orphan = context.getDatabasePath("$namespace-profile-${UUID.randomUUID()}.db")
        orphan.writeText("Synthetic abandoned stage"); File(orphan.path + "-wal").writeText("Synthetic WAL")
        store.deleteAll(source.ledger.settings)
        val result = store.session.value.repository.read()
        assertTrue(result.accounts.isEmpty() && result.transactions.isEmpty() && result.evidence.isEmpty() && result.importBatches.isEmpty())
        assertEquals(listOf(UNCATEGORIZED_ID), result.categories.map { it.value.id })
        assertFalse(orphan.exists()); assertFalse(File(orphan.path + "-wal").exists())
        assertTrue(exported.exists()); assertTrue(preferences.getBoolean("app_lock", false))
        assertFalse(store.session.value.cleanupPending)
        store.close(); store = LocalProfileStore(context, storageClock, namespace)
        assertTrue(store.session.value.repository.read().transactions.isEmpty())
    }

    @Test fun editedImportedRowsRemainProtectedAfterBackupAndRestore() = runBlocking {
        source()
        val repo = store.session.value.repository
        val row = repo.read().transactions.single { it.details.source == TransactionSource.CSV }
        repo.applyTransactions(listOf(TransactionWrite(row.transaction, row.details.copy(notes = "Synthetic correction"), row.metadata.revision)))
        val source = store.backup()
        store.restore(source, source.ledger.settings)
        val restored = store.session.value.repository
        val before = restored.read()
        try { restored.undoCsv(before.importBatches.single().id, before.settings.revision); fail("Edited ownership guard must survive") } catch (_: CsvUndoConflict) {}
        assertEquals(before, restored.read())
    }

    @Test fun undoneCreatedAndMatchedBatchesRestoreWithoutChangingTheOriginalManualRecord() = runBlocking {
        source()
        val repo = store.session.value.repository
        val manual = repo.read().transactions.single { it.transaction.id == UUID(0, 102) }
        val document = CsvReader.read("date,amount,direction,merchant,category,reference\n2026-11-02,2750.25,DEBIT,Different synthetic description,Food,SYN-MATCH-RESTORE\n".byteInputStream())
        val matchedBatch = repo.commitCsv(CsvImportRequest(document, CsvMapping.forHeaders(document.headers), recoveryBank,
            mapOf(2 to CsvDecision(CsvAction.ATTACH, existingId = manual.transaction.id)), repo.read().settings.revision))
        val batchIds = repo.read().importBatches.map { it.id }
        batchIds.forEach { repo.undoCsv(it, repo.read().settings.revision) }
        val backup = store.backup()
        store.restore(backup, backup.ledger.settings)
        val restored = store.session.value.repository
        assertTrue(restored.read().importBatches.all { it.isUndone })
        batchIds.forEach { restored.restoreCsv(it, restored.read().settings.revision) }
        restored.undoCsv(matchedBatch, restored.read().settings.revision)
        assertEquals(manual, restored.read().transactions.single { it.transaction.id == manual.transaction.id })
        assertEquals(325_849L, LedgerCalculator(storageClock).monthlyReport(restored.read().snapshot, YearMonth.of(2026, 11)).netSpendingMinor)
    }

    @Test fun cleanupExceptionRemainsVisibleUntilSuccessfulRetry() = runBlocking {
        source()
        val oldName = store.session.value.databaseName
        store.close()
        var failCleanup = true
        val failingContext = object : ContextWrapper(context) {
            override fun deleteDatabase(name: String): Boolean {
                if (failCleanup && name == oldName) throw java.io.IOException("Synthetic cleanup failure")
                return super.deleteDatabase(name)
            }
        }
        store = LocalProfileStore(failingContext, storageClock, namespace)
        store.deleteAll(store.session.value.repository.read().settings)
        assertTrue(store.session.value.repository.read().transactions.isEmpty())
        assertTrue(store.session.value.cleanupPending)
        assertTrue(context.getDatabasePath(oldName).exists())
        failCleanup = false
        store.retryCleanup()
        assertFalse(store.session.value.cleanupPending)
        assertFalse(context.getDatabasePath(oldName).exists())
    }
}
