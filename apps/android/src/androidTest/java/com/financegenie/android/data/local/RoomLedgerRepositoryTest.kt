package com.financegenie.android.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.data.DuplicateRecordException
import com.financegenie.android.data.RecordMetadata
import com.financegenie.android.data.SourceEvidenceInput
import com.financegenie.android.data.StaleRecordException
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionSource
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.AdjustmentDirection
import com.financegenie.android.domain.BalanceObservation
import com.financegenie.android.domain.DateRange
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.LedgerValidator
import com.financegenie.android.domain.Money
import com.financegenie.android.domain.MonthlyBudget
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.domain.UNCATEGORIZED_ID
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLedgerRepositoryTest {
    private lateinit var database: FinanceGenieDatabase
    private lateinit var repository: RoomLedgerRepository
    private val month = YearMonth.of(2026, 11)

    @Before
    fun setUp() {
        database = FinanceGenieDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext, storageClock)
        repository = RoomLedgerRepository(database, storageClock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyDatabaseSeedsOnlyStableLedgerDefaults() = runBlocking<Unit> {
        val first = repository.read()
        assertEquals(first, repository.read())
        assertEquals(0L, first.settings.revision)
        assertEquals("INR", first.settings.currency)
        assertEquals(LedgerDates.reportingZone.id, first.settings.reportingZone)
        assertEquals(listOf(UNCATEGORIZED_ID), first.categories.map { it.value.id })
        assertTrue(first.accounts.isEmpty())
        assertTrue(first.transactions.isEmpty())
        assertEquals(1, database.openHelper.readableDatabase.version)
        database.openHelper.readableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun revisionCheckedReadsSeeWritesFromAnotherRepository() = runBlocking<Unit> {
        seedAccounts()
        val first = repository.read()
        val other = RoomLedgerRepository(database, storageClock)
        other.saveCategory(LedgerCategory("shopping", "Shopping"))
        val updated = repository.read()
        assertEquals(first.settings.revision + 1, updated.settings.revision)
        assertTrue(updated.categories.any { it.value.id == "shopping" })
        val otherBefore = other.read()
        val event = storageExpense()
        repository.applyTransactions(listOf(TransactionWrite(event)))
        val otherAfter = other.read()
        assertEquals(otherBefore.settings.revision + 1, otherAfter.settings.revision)
        assertEquals(event, otherAfter.transactions.single().transaction)
        assertEquals("manual:${event.id}", otherAfter.evidence.single().input.sourceKey)
        assertEquals(otherAfter, repository.read())
    }

    @Test
    fun exactMoneyDatesInstantsNotesBudgetsAndObservationsRoundTrip() = runBlocking<Unit> {
        repository.saveAccount(storageAccounts()[0].copy(openingBalanceMinor = Money.INPUT_MAX_MINOR))
        repository.saveAccount(storageAccounts()[2].copy(openingBalanceMinor = -Money.INPUT_MAX_MINOR))
        repository.saveCategory(LedgerCategory("food", "Food"))
        val event = storageExpense(amount = 1).copy(
            occurredAt = Instant.parse("2026-11-02T12:34:56.123456789Z"),
            originalZoneId = "UTC",
        )
        val details = TransactionDetails("Synthetic 100%_shop", "Synthetic private note")
        repository.applyTransactions(listOf(TransactionWrite(event, details)))
        val adjustment = storageExpense(101, 5).copy(
            kind = TransactionKind.ADJUSTMENT,
            adjustmentDirection = AdjustmentDirection.DECREASE,
            adjustmentReason = "Synthetic adjustment",
        )
        repository.applyTransactions(listOf(TransactionWrite(adjustment)))
        val budget = MonthlyBudget(UUID(0, 200), month, 0, "food")
        val observation = BalanceObservation(UUID(0, 300), storageCardId, LocalDate.of(2026, 11, 2), -Money.INPUT_MAX_MINOR)
        repository.saveBudget(budget)
        repository.saveObservation(observation)
        val stored = repository.read()
        assertEquals(event, stored.transactions.first { it.transaction.id == event.id }.transaction)
        assertEquals(details, stored.transactions.first { it.transaction.id == event.id }.details)
        assertEquals(adjustment, stored.transactions.first { it.transaction.id == adjustment.id }.transaction)
        assertEquals(budget, stored.budgets.single().value)
        assertEquals(observation, stored.observations.single().value)
        assertEquals(Money.INPUT_MAX_MINOR - 6, LedgerCalculator(storageClock).monthlyReport(stored.snapshot, month).accountPositionsMinor.getValue(storageBankId))
        assertEquals(2, stored.evidence.size)
    }

    @Test
    fun rolledBackOuterTransactionCannotPoisonAReusedRevision() = runBlocking<Unit> {
        checkRolledBackOuterTransaction(switchDispatcher = false)
    }

    @Test
    fun rolledBackOuterTransactionAcrossDispatchersCannotPoisonAReusedRevision() = runBlocking<Unit> {
        checkRolledBackOuterTransaction(switchDispatcher = true)
    }

    @Test
    fun rolledBackSnapshotCannotHideACommitThroughAnotherDatabaseConnection() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cache-rollback-test-${UUID.randomUUID()}.db"
        val firstDatabase = FinanceGenieDatabase.open(context, name, storageClock)
        try {
            val first = RoomLedgerRepository(firstDatabase, storageClock)
            val before = first.read()
            expectFailure<RollbackProbe> {
                firstDatabase.withTransaction {
                    first.saveCategory(LedgerCategory("rolled_back", "Rolled back synthetic category"))
                    assertTrue(first.read().categories.any { it.value.id == "rolled_back" })
                    throw RollbackProbe()
                }
            }
            val secondDatabase = FinanceGenieDatabase.open(context, name, storageClock)
            try {
                val second = RoomLedgerRepository(secondDatabase, storageClock)
                second.saveCategory(LedgerCategory("committed", "Committed synthetic category"))
                val committed = second.read()
                assertEquals(before.settings.revision + 1, committed.settings.revision)
                assertEquals(committed, first.read())
                assertFalse(first.read().categories.any { it.value.id == "rolled_back" })
            } finally { secondDatabase.close() }
        } finally {
            firstDatabase.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun duplicateStableIdsAndStaleEditsCannotOverwriteRecords() = runBlocking<Unit> {
        seedAccounts()
        val event = storageExpense()
        repository.applyTransactions(listOf(TransactionWrite(event)))
        val beforeDuplicate = repository.read()
        expectFailure<DuplicateRecordException> { repository.applyTransactions(listOf(TransactionWrite(event))) }
        assertEquals(beforeDuplicate, repository.read())
        expectFailure<DuplicateRecordException> { repository.saveAccount(storageAccounts()[0]) }
        repository.applyTransactions(listOf(TransactionWrite(event.copy(amountMinor = 19_950), expectedRevision = 1)))
        val beforeStale = repository.read()
        expectFailure<StaleRecordException> {
            repository.applyTransactions(listOf(TransactionWrite(event.copy(amountMinor = 10_000), expectedRevision = 1)))
        }
        assertEquals(beforeStale, repository.read())
        assertEquals(19_950L, beforeStale.transactions.single().transaction.amountMinor)
        assertEquals(2L, beforeStale.transactions.single().metadata.revision)
    }

    @Test
    fun deleteAndUndoPreserveProvenanceAndRejectStaleOrRepeatedUndo() = runBlocking<Unit> {
        seedAccounts()
        val event = storageExpense()
        val creation = repository.applyTransactions(listOf(TransactionWrite(event)))
        val deletion = repository.applyTransactions(listOf(TransactionWrite(event.copy(isDeleted = true), expectedRevision = 1)))
        assertTrue(repository.transactions(DateRange.month(month)).isEmpty())
        assertTrue(repository.read().transactions.single().transaction.isDeleted)
        repository.undoTransactionChange(deletion)
        val restored = repository.read()
        assertEquals(event, restored.transactions.single().transaction)
        assertEquals(3L, restored.transactions.single().metadata.revision)
        assertEquals(1, restored.evidence.size)
        expectFailure<IllegalStateException> { repository.undoTransactionChange(deletion) }
        expectFailure<StaleRecordException> { repository.undoTransactionChange(creation) }
        assertEquals(restored, repository.read())
    }

    @Test
    fun deletingAndUndoingACreationKeepsAReplayProtectedTombstone() = runBlocking<Unit> {
        seedAccounts()
        val event = storageExpense()
        val group = repository.applyTransactions(listOf(TransactionWrite(event)))
        repository.undoTransactionChange(group)
        val stored = repository.read()
        assertTrue(stored.transactions.single().transaction.isDeleted)
        assertEquals("manual:${event.id}", stored.evidence.single().input.sourceKey)
        expectFailure<DuplicateRecordException> { repository.applyTransactions(listOf(TransactionWrite(event))) }
        assertEquals(stored, repository.read())
    }

    @Test
    fun linkedEventsCanBeInsertedOutOfOrderAndCorrectedOnlyAsAValidGroup() = runBlocking<Unit> {
        seedAccounts()
        val expense = storageExpense(amount = 10_000)
        val refund = storageExpense(101, 2_500, accountId = storageCardId).copy(
            kind = TransactionKind.REFUND,
            originalExpenseId = expense.id,
        )
        repository.applyTransactions(listOf(TransactionWrite(refund), TransactionWrite(expense)))
        val before = repository.read()
        val historyCount = database.ledgerDao().groupCount()
        expectFailure<IllegalArgumentException> {
            repository.applyTransactions(listOf(TransactionWrite(expense.copy(amountMinor = 2_499), expectedRevision = 1)))
        }
        expectFailure<IllegalArgumentException> {
            repository.applyTransactions(listOf(TransactionWrite(expense.copy(isDeleted = true), expectedRevision = 1)))
        }
        assertEquals(before, repository.read())
        assertEquals(historyCount, database.ledgerDao().groupCount())
        val group = repository.applyTransactions(listOf(
            TransactionWrite(expense.copy(isDeleted = true), expectedRevision = 1),
            TransactionWrite(refund.copy(isDeleted = true), expectedRevision = 1),
        ))
        assertEquals(0L, LedgerCalculator(storageClock).monthlyReport(repository.read().snapshot, month).netSpendingMinor)
        repository.undoTransactionChange(group)
        assertEquals(7_500L, LedgerCalculator(storageClock).monthlyReport(repository.read().snapshot, month).netSpendingMinor)
    }

    @Test
    fun evidenceConstraintFailureRollsBackEventsHistoryAndLedgerRevision() = runBlocking<Unit> {
        seedAccounts()
        val first = storageExpense()
        val firstEvidence = SourceEvidenceInput(UUID(0, 500), first.id, TransactionSource.CSV, "synthetic-csv-reference:alpha")
        repository.applyTransactions(listOf(TransactionWrite(first)), listOf(firstEvidence))
        val before = repository.read()
        val groups = database.ledgerDao().groupCount()
        val second = storageExpense(101)
        expectFailure<SQLiteConstraintException> {
            repository.applyTransactions(
                listOf(TransactionWrite(second)),
                listOf(firstEvidence.copy(id = UUID(0, 501), transactionId = second.id)),
            )
        }
        assertEquals(before, repository.read())
        assertEquals(groups, database.ledgerDao().groupCount())
        assertEquals(before, RoomLedgerRepository(database, storageClock).read())
    }

    @Test
    fun undoDetachesNewEvidenceFromExistingRecordsButRetainsReplayKeys() = runBlocking<Unit> {
        seedAccounts()
        val event = storageExpense()
        repository.applyTransactions(listOf(TransactionWrite(event)))
        val proof = SourceEvidenceInput(UUID(0, 500), event.id, TransactionSource.CSV, "synthetic-csv-reference:beta")
        val group = repository.applyTransactions(listOf(TransactionWrite(event, expectedRevision = 1)), listOf(proof))
        repository.undoTransactionChange(group)
        val evidence = repository.read().evidence.first { it.input.id == proof.id }
        assertFalse(evidence.isActive)
        assertEquals(proof.sourceKey, evidence.input.sourceKey)
        assertEquals(event, repository.read().transactions.single().transaction)
    }

    @Test
    fun overallBudgetUniquenessIsEnforcedEvenWhenCategoryIsNull() = runBlocking<Unit> {
        val budget = MonthlyBudget(UUID(0, 200), month, 100_000)
        repository.saveBudget(budget)
        val duplicate = budget.copy(id = UUID(0, 201))
        expectFailure<IllegalArgumentException> { repository.saveBudget(duplicate) }
        expectFailure<SQLiteConstraintException> {
            database.ledgerDao().insertBudget(duplicate.toEntity(RecordMetadata(1, 0, 0)))
        }
        assertEquals(listOf(budget), repository.read().budgets.map { it.value })
    }

    @Test
    fun foreignKeysProtectHistoryAndArchiveDoesNotRemoveFinancialEffects() = runBlocking<Unit> {
        seedAccounts()
        val event = storageExpense()
        repository.applyTransactions(listOf(TransactionWrite(event)))
        expectFailure<SQLiteConstraintException> {
            database.openHelper.writableDatabase.execSQL("DELETE FROM accounts WHERE id = ?", arrayOf(storageBankId.toString()))
        }
        repository.saveAccount(storageAccounts()[0].copy(isArchived = true), expectedRevision = 1)
        assertEquals(-24_950L, LedgerCalculator(storageClock).monthlyReport(repository.read().snapshot, month).totalTrackedPositionMinor)
        expectFailure<IllegalArgumentException> { repository.applyTransactions(listOf(TransactionWrite(storageExpense(101)))) }
        assertEquals(1, repository.read().transactions.size)
    }

    @Test
    fun queriesArePagedDeterministicBoundedAndTreatSearchWildcardsLiterally() = runBlocking<Unit> {
        seedAccounts()
        val first = storageExpense(100, 100, LocalDate.of(2026, 11, 1))
        val second = storageExpense(101, 200, accountId = storageCashId)
        val transfer = storageExpense(102, 300, LocalDate.of(2026, 11, 3)).copy(
            kind = TransactionKind.TRANSFER, destinationAccountId = storageCashId, categoryId = null,
        )
        val deleted = storageExpense(103, 50)
        repository.applyTransactions(listOf(
            TransactionWrite(first, TransactionDetails("Synthetic 100%_Shop")),
            TransactionWrite(second, TransactionDetails("Synthetic 1000 Shop")),
            TransactionWrite(transfer), TransactionWrite(deleted),
        ))
        repository.applyTransactions(listOf(TransactionWrite(deleted.copy(isDeleted = true), expectedRevision = 1)))
        val range = DateRange.month(month)
        assertEquals(listOf(transfer.id, second.id), repository.transactions(range, limit = 2).map { it.transaction.id })
        assertEquals(listOf(first.id), repository.transactions(range, limit = 2, offset = 2).map { it.transaction.id })
        assertEquals(listOf(transfer.id, second.id), repository.transactions(range, accountId = storageCashId).map { it.transaction.id })
        assertEquals(listOf(second.id, first.id), repository.transactions(range, categoryId = "food").map { it.transaction.id })
        assertEquals(listOf(first.id), repository.transactions(range, search = "%_").map { it.transaction.id })
        assertTrue(repository.transactions(range, search = "' OR 1=1 --").isEmpty())
        assertEquals(listOf(second.id), repository.transactions(DateRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))).map { it.transaction.id })
        expectFailure<IllegalArgumentException> { repository.transactions(range, limit = 0) }
        expectFailure<IllegalArgumentException> { repository.transactions(range, offset = -1) }
    }

    @Test
    fun observableSnapshotsNeverExposeAHalfCommittedGroup() = runBlocking<Unit> {
        seedAccounts()
        val initial = CompletableDeferred<Unit>()
        val observed = async(Dispatchers.Default) {
            repository.observe().onEach { initial.complete(Unit) }.take(2).toList()
        }
        withTimeout(10_000) { initial.await() }
        val expense = storageExpense(amount = 10_000)
        val refund = storageExpense(101, 2_500).copy(kind = TransactionKind.REFUND, originalExpenseId = expense.id)
        repository.applyTransactions(listOf(TransactionWrite(refund), TransactionWrite(expense)))
        val snapshots = withTimeout(10_000) { observed.await() }
        assertTrue(snapshots.first().transactions.isEmpty())
        assertEquals(2, snapshots.last().transactions.size)
        assertEquals(snapshots.first().settings.revision + 1, snapshots.last().settings.revision)
        LedgerValidator(storageClock).validate(snapshots.last().snapshot)
    }

    @Test
    fun concurrentEditorsWithSameBaseRevisionCannotLoseAnAcceptedEdit() = runBlocking<Unit> {
        seedAccounts()
        val event = storageExpense()
        repository.applyTransactions(listOf(TransactionWrite(event)))
        val results = coroutineScope {
            listOf(19_950L, 14_950L).map { amount ->
                async(Dispatchers.Default) {
                    try {
                        repository.applyTransactions(listOf(TransactionWrite(event.copy(amountMinor = amount), expectedRevision = 1)))
                        "saved"
                    } catch (_: StaleRecordException) {
                        "stale"
                    }
                }
            }.awaitAll()
        }
        assertEquals(listOf("saved", "stale"), results.sorted())
        assertEquals(2L, repository.read().transactions.single().metadata.revision)
    }

    @Test
    fun schemaUsesIntegerMoneyAndContainsNoRawMessageBodyColumn() = runBlocking<Unit> {
        repository.read()
        val columns = mutableMapOf<String, String>()
        database.openHelper.readableDatabase.query("PRAGMA table_info(transactions)").use { cursor ->
            while (cursor.moveToNext()) columns[cursor.getString(1)] = cursor.getString(2)
        }
        assertEquals("INTEGER", columns.getValue("amount_minor"))
        assertFalse(columns.keys.any { it.contains("body") || it.contains("raw_message") })
    }

    @Test
    fun sharedGoldenFixtureKeepsExactTotalsAfterDatabaseRoundTrip() = runBlocking<Unit> {
        val fixture = StorageGoldenFixture.load()
        fixture.snapshot.accounts.forEach { repository.saveAccount(it) }
        fixture.snapshot.categories.filterNot { it.id == UNCATEGORIZED_ID }.forEach { repository.saveCategory(it) }
        fixture.snapshot.budgets.forEach { repository.saveBudget(it) }
        repository.applyTransactions(fixture.snapshot.transactions.reversed().map { TransactionWrite(it) })
        val stored = repository.read()
        val report = LedgerCalculator(storageClock).monthlyReport(stored.snapshot, fixture.month)
        assertEquals(fixture.expected.minor("grossSpendingMinor"), report.grossSpendingMinor)
        assertEquals(fixture.expected.minor("refundsMinor"), report.refundsMinor)
        assertEquals(fixture.expected.minor("netSpendingMinor"), report.netSpendingMinor)
        assertEquals(fixture.expected.minor("incomeMinor"), report.incomeMinor)
        assertEquals(fixture.expected.minor("recordedSurplusMinor"), report.recordedSurplusMinor)
        assertEquals(fixture.expected.minor("totalTrackedPositionMinor"), report.totalTrackedPositionMinor)
        val positions = fixture.expected.getJSONObject("accountPositionsMinor")
        report.accountPositionsMinor.forEach { (id, value) -> assertEquals(positions.minor(id.toString()), value) }
        val remaining = fixture.expected.getJSONObject("budgetRemainingMinor")
        stored.budgets.forEach { record ->
            assertEquals(remaining.minor(record.value.id.toString()), LedgerCalculator(storageClock).budgetProgress(record.value, report).remainingMinor)
        }
    }

    private suspend fun seedAccounts() {
        storageAccounts().forEach { repository.saveAccount(it) }
        repository.saveCategory(LedgerCategory("food", "Food"))
    }

    private suspend fun checkRolledBackOuterTransaction(switchDispatcher: Boolean) {
        seedAccounts()
        val before = repository.read()
        expectFailure<RollbackProbe> {
            database.withTransaction {
                repository.saveCategory(LedgerCategory("rolled_back", "Rolled back synthetic category"))
                val uncommitted = if (switchDispatcher) withContext(Dispatchers.Default) { repository.read() } else repository.read()
                assertEquals(before.settings.revision + 1, uncommitted.settings.revision)
                assertTrue(uncommitted.categories.any { it.value.id == "rolled_back" })
                throw RollbackProbe()
            }
        }
        // A different repository can legitimately reuse the revision after rollback.
        val other = RoomLedgerRepository(database, storageClock)
        other.saveCategory(LedgerCategory("committed", "Committed synthetic category"))
        val committed = other.read()
        assertEquals(before.settings.revision + 1, committed.settings.revision)
        assertEquals(committed, repository.read())
        assertFalse(repository.read().categories.any { it.value.id == "rolled_back" })
    }

    private class RollbackProbe : RuntimeException()

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline action: suspend () -> Unit) {
        val failure = try {
            action()
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue("Expected ${T::class.java.simpleName}, got ${failure?.javaClass?.simpleName ?: "success"}", failure is T)
    }
}
