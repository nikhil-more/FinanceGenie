package com.financegenie.android.data.local

import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.csv.*
import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CsvRepositoryTest {
    private lateinit var database: FinanceGenieDatabase
    private lateinit var repository: RoomLedgerRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC)
    private val header = "date,amount,direction,merchant,category,reference\n"
    @Before fun setUp() = runBlocking<Unit> {
        database = FinanceGenieDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext, clock)
        repository = RoomLedgerRepository(database, clock)
        storageAccounts().forEach { repository.saveAccount(it) }
        repository.saveCategory(LedgerCategory("food", "Food"))
    }
    @After fun tearDown() { database.close() }
    private fun document(rows: String) = CsvReader.read((header + rows).byteInputStream())
    private suspend fun request(document: CsvDocument): CsvImportRequest {
        val ledger = repository.read()
        return CsvImportRequest(document, CsvMapping.forHeaders(document.headers), storageBankId, emptyMap(), ledger.settings.revision)
    }
    private suspend fun acceptDebits(document: CsvDocument): CsvImportRequest {
        val base = request(document)
        val plan = planCsvImport(repository.read(), base, clock)
        return base.copy(decisions = plan.rows.filter { it.parsed.problem == null && it.parsed.direction == CsvDirection.DEBIT && it.matches.isEmpty() }
            .associate { it.parsed.number to CsvDecision(CsvAction.CREATE, categoryId = it.parsed.categoryId) })
    }
    private suspend inline fun <reified T : Throwable> failure(crossinline action: suspend () -> Unit) {
        val error = try { action(); null } catch (error: Throwable) { error }
        assertTrue("Expected ${T::class.java.simpleName}, got ${error?.javaClass?.simpleName}", error is T)
    }

    @Test fun hundredRowsCommitExactAcceptedSubsetAndPreserveManualMatches() = runBlocking {
        val document = InstrumentationRegistry.getInstrumentation().context.assets.open("csv/acceptance-100.csv").use(CsvReader::read)
        for (index in listOf(5, 6)) repository.applyTransactions(listOf(TransactionWrite(storageExpense(500L + index, if (index == 5) 1_100 else 1_200), TransactionDetails("Synthetic merchant $index"))))
        val before = repository.read()
        val base = acceptDebits(document)
        val initial = planCsvImport(before, base, clock)
        assertEquals(78, initial.acceptedCount)
        assertEquals(10, initial.invalidCount)
        assertEquals(12, initial.reviewCount)
        assertEquals(2, initial.duplicateCount)
        val choices = base.decisions.toMutableMap()
        for (row in initial.rows.filter { it.matches.isNotEmpty() }) choices[row.parsed.number] = CsvDecision(CsvAction.ATTACH, existingId = row.matches.single().transaction.id)
        choices[82] = CsvDecision(CsvAction.CREATE, TransactionKind.INCOME)
        choices[83] = CsvDecision(CsvAction.CREATE, TransactionKind.TRANSFER, otherAccountId = storageCashId)
        choices[84] = CsvDecision(CsvAction.CREATE, TransactionKind.REFUND, "food", confirmedUnlinked = true)
        val accepted = base.copy(decisions = choices)
        val plan = planCsvImport(before, accepted, clock)
        assertEquals(83, plan.acceptedCount)
        assertEquals(81, plan.writes.size)
        assertEquals(7, plan.reviewCount)
        val id = repository.commitCsv(accepted)
        val committed = repository.read()
        assertEquals(83, committed.transactions.size)
        assertEquals(83, committed.importBatches.single().acceptedCount)
        assertEquals(id, committed.importBatches.single().id)
        assertEquals(before.transactions, committed.transactions.filter { it.details.source == TransactionSource.MANUAL })
        val report = LedgerCalculator(clock).monthlyReport(committed.snapshot, YearMonth.of(2026, 11))
        assertEquals(76_550L, report.netSpendingMinor)
        assertEquals(2_000L, report.incomeMinor)
        assertEquals(-72_550L, report.accountPositionsMinor.getValue(storageBankId))
        assertEquals(-2_000L, report.accountPositionsMinor.getValue(storageCashId))
        assertEquals(83, committed.evidence.count { it.input.importBatchId == id })
        assertFalse(committed.evidence.any { it.input.sourceKey.contains("SYN-CSV") })
        repository.undoCsv(id, committed.settings.revision)
        val undone = repository.read()
        assertEquals(81, undone.transactions.count { it.transaction.isDeleted })
        assertEquals(before.transactions, undone.transactions.filter { it.details.source == TransactionSource.MANUAL })
        assertTrue(undone.importBatches.single().isUndone)
        assertTrue(undone.evidence.filter { it.input.importBatchId == id }.none { it.isActive })
        repository.restoreCsv(id, undone.settings.revision)
        val restored = repository.read()
        assertFalse(restored.importBatches.single().isUndone)
        assertTrue(restored.transactions.none { it.transaction.isDeleted })
        assertEquals(report, LedgerCalculator(clock).monthlyReport(restored.snapshot, YearMonth.of(2026, 11)))
        repository.undoCsv(id, restored.settings.revision)
        assertEquals(81, repository.read().transactions.count { it.transaction.isDeleted })
    }

    @Test fun unchangedRemappedAndDifferentAccountReplaysNeverWriteAnotherBatch() = runBlocking {
        val document = document("2026-11-02,1.25,DEBIT,Synthetic,Food,REF\n")
        val accepted = acceptDebits(document)
        val id = repository.commitCsv(accepted)
        for (mapping in listOf(accepted.mapping, accepted.mapping.copy(merchant = -1))) {
            for (account in listOf(storageBankId, storageCashId)) {
                val before = repository.read()
                failure<CsvReplayException> { repository.commitCsv(accepted.copy(mapping = mapping, accountId = account, expectedLedgerRevision = before.settings.revision)) }
                assertEquals(before, repository.read())
            }
        }
        repository.undoCsv(id, repository.read().settings.revision)
        val undone = repository.read()
        failure<CsvReplayException> { repository.commitCsv(accepted.copy(expectedLedgerRevision = undone.settings.revision)) }
        assertEquals(undone, repository.read())
    }

    @Test fun reorderedAndOverlappingFilesAttachEvidenceWithoutDuplicatingExpenses() = runBlocking {
        val a = "2026-11-02,1.25,DEBIT,Synthetic A,Food,REF-A\n"
        val b = "2026-11-02,2.00,DEBIT,Synthetic B,Food,REF-B\n"
        val first = repository.commitCsv(acceptDebits(document(a + b)))
        val before = repository.read()
        val base = request(document(b + a))
        val matches = planCsvImport(before, base, clock)
        assertEquals(2, matches.duplicateCount)
        assertEquals(0, matches.acceptedCount)
        val second = repository.commitCsv(base.copy(decisions = matches.rows.associate { it.parsed.number to CsvDecision(CsvAction.ATTACH, existingId = it.matches.single().transaction.id) }))
        val linked = repository.read()
        assertEquals(before.transactions, linked.transactions)
        failure<CsvUndoConflict> { repository.undoCsv(first, linked.settings.revision) }
        assertEquals(linked, repository.read())
        repository.undoCsv(second, linked.settings.revision)
        assertEquals(before.transactions, repository.read().transactions)
        repository.undoCsv(first, repository.read().settings.revision)
        assertTrue(repository.read().transactions.all { it.transaction.isDeleted })
        failure<CsvUndoConflict> { repository.restoreCsv(second, repository.read().settings.revision) }
    }

    @Test fun editedRowsBlockWholeBatchUndoAndRestoreWithoutRewindingCorrections() = runBlocking {
        val id = repository.commitCsv(acceptDebits(document("2026-11-02,1.25,DEBIT,Synthetic,Food,\n2026-11-02,2.00,DEBIT,Synthetic 2,Food,\n")))
        val record = repository.read().transactions.first()
        repository.applyTransactions(listOf(TransactionWrite(record.transaction.copy(amountMinor = 126), record.details, record.metadata.revision)))
        val edited = repository.read()
        failure<CsvUndoConflict> { repository.undoCsv(id, edited.settings.revision) }
        assertEquals(edited, repository.read())
        val cleanId = repository.commitCsv(acceptDebits(document("2026-11-03,3.00,DEBIT,Synthetic restore conflict,Food,SYN-RESTORE\n")))
        repository.undoCsv(cleanId, repository.read().settings.revision)
        val tombstone = repository.read().transactions.single { it.transaction.isDeleted }
        repository.applyTransactions(listOf(TransactionWrite(tombstone.transaction, tombstone.details.copy(notes = "Synthetic later correction"), tombstone.metadata.revision)))
        val changedAfterUndo = repository.read()
        failure<CsvUndoConflict> { repository.restoreCsv(cleanId, changedAfterUndo.settings.revision) }
        assertEquals(changedAfterUndo, repository.read())
    }

    @Test fun newlyLinkedRefundBlocksDeletingItsImportedPurchase() = runBlocking {
        val id = repository.commitCsv(acceptDebits(document("2026-11-02,1.25,DEBIT,Synthetic,Food,\n")))
        val original = repository.read().transactions.single().transaction
        val refund = storageExpense(900, 25, LocalDate.of(2026, 11, 3)).copy(kind = TransactionKind.REFUND, originalExpenseId = original.id)
        repository.applyTransactions(listOf(TransactionWrite(refund)))
        val before = repository.read()
        failure<CsvUndoConflict> { repository.undoCsv(id, before.settings.revision) }
        assertEquals(before, repository.read())
    }

    @Test fun failureAfterFinancialInsertsRollsBackBatchEvidenceHistoryAndRevision() = runBlocking {
        val accepted = acceptDebits(document("2026-11-02,1.25,DEBIT,Synthetic,Food,\n"))
        val before = repository.read()
        val groups = database.ledgerDao().groupCount()
        database.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER csv_test_failure BEFORE INSERT ON source_evidence WHEN NEW.source = 'CSV' BEGIN SELECT RAISE(ABORT, 'synthetic CSV write failure'); END")
        try {
            failure<android.database.sqlite.SQLiteConstraintException> { repository.commitCsv(accepted) }
            assertEquals(before, RoomLedgerRepository(database, clock).read())
            assertEquals(groups, database.ledgerDao().groupCount())
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER csv_test_failure") }
    }

    @Test fun stalePreviewAndRacingCommitsCannotDoubleAnImport() = runBlocking {
        val accepted = acceptDebits(document("2026-11-02,1.25,DEBIT,Synthetic,Food,\n"))
        val results = coroutineScope { listOf(async { runCatching { repository.commitCsv(accepted) } }, async { runCatching { repository.commitCsv(accepted) } }).map { it.await() } }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is StaleRecordException)
        assertEquals(1, repository.read().transactions.size)
        assertEquals(1, repository.read().importBatches.size)
        val current = repository.read()
        failure<StaleRecordException> { repository.undoCsv(current.importBatches.single().id, current.settings.revision - 1) }
        assertEquals(current, repository.read())
    }

    @Test fun previewAndExclusionDoNotWriteAndGenericUndoCannotBypassBatchChecks() = runBlocking {
        val document = document("2026-11-02,1.25,DEBIT,Synthetic,Food,\n")
        val before = repository.read()
        val excluded = request(document).copy(decisions = mapOf(2 to CsvDecision(CsvAction.EXCLUDE)))
        assertFalse(planCsvImport(before, excluded, clock).canCommit)
        failure<IllegalArgumentException> { repository.commitCsv(excluded) }
        assertEquals(before, repository.read())
        val id = repository.commitCsv(acceptDebits(document))
        val committed = repository.read()
        val group = committed.evidence.single { it.input.importBatchId == id }.changeGroupId
        failure<IllegalArgumentException> { repository.undoTransactionChange(group) }
        assertEquals(committed, repository.read())
    }
}
