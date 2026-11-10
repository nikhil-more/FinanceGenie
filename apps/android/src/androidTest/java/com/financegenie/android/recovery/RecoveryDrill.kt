package com.financegenie.android.recovery

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.FinanceGenieApplication
import com.financegenie.android.csv.*
import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import java.io.File
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Only the separately installed, disposable .recoverycheck app may run this drill. */
class RecoveryDrill {
    @Test fun freshInstallRecoveryAndDurableDelete() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Use the isolated recovery device driver", context.packageName.endsWith(".recoverycheck"))
        val phase = InstrumentationRegistry.getArguments().getString("recoveryDrillPhase")
        assumeTrue("Host-coordinated drill only", phase in listOf("seed", "restore", "verify"))
        val container = (context.applicationContext as FinanceGenieApplication).container
        val profiles = requireNotNull(container.profiles)
        val file = File(context.filesDir, "recovery-drill-only.json")
        val pid = File(context.filesDir, "recovery-drill-only.pid")
        when (phase) {
            "seed" -> {
                assertTrue(container.repository.read().accounts.isEmpty())
                seedRecovery(container.repository)
                val backup = profiles.backup()
                file.outputStream().use { BackupCodec.write(backup, it, container.clock) }
                assertEquals(1_000, backup.ledger.transactions.size)
            }
            "restore" -> {
                // The host uninstalled this synthetic package and reinstalled the APK.
                val empty = container.repository.read()
                assertTrue(empty.accounts.isEmpty() && empty.transactions.isEmpty() && empty.evidence.isEmpty())
                assertFalse(container.privacy.state.value.appLockEnabled)
                assertTrue(container.privacy.state.value.protectScreen)
                val backup = file.inputStream().use { BackupCodec.read(it, container.clock) }
                profiles.restore(backup, empty.settings)
                pid.writeText(Process.myPid().toString())
            }
            "verify" -> {
                assertNotEquals(pid.readText().toInt(), Process.myPid())
                val source = file.inputStream().use { BackupCodec.read(it, container.clock) }
                val restored = profiles.backup()
                assertEquals(source.forNewProfile(restored.ledger.settings.localId).ordered(), restored.copy(createdAtMillis = source.createdAtMillis).ordered())
                val report = LedgerCalculator(container.clock).monthlyReport(restored.ledger.snapshot, YearMonth.of(2026, 11))
                assertEquals(325_849L, report.netSpendingMinor)
                assertEquals(6_000_000L, report.incomeMinor)
                assertEquals(5_174_151L, report.accountPositionsMinor[recoveryBank])
                val document = CsvReader.read(recoveryCsv.byteInputStream())
                try { container.repository.commitCsv(CsvImportRequest(document, CsvMapping.forHeaders(document.headers), recoveryBank,
                    mapOf(2 to CsvDecision(CsvAction.CREATE, categoryId = "food")), restored.ledger.settings.revision)); fail("Replay must remain blocked") }
                catch (_: CsvReplayException) {}
                container.repository.undoCsv(restored.ledger.importBatches.single().id, container.repository.read().settings.revision)
                container.repository.restoreCsv(restored.ledger.importBatches.single().id, container.repository.read().settings.revision)
                profiles.deleteAll(container.repository.read().settings)
                assertTrue(container.repository.read().transactions.isEmpty())
                assertTrue(file.exists()) // Local deletion never deletes exported documents.
                file.delete(); pid.delete()
            }
        }
    }
}
