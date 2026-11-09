package com.financegenie.android.data.local

import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.data.StaleRecordException
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.MonthlyBudget
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BudgetRepositoryTest {
    private lateinit var database: FinanceGenieDatabase
    private lateinit var repository: RoomLedgerRepository
    private val november = YearMonth.of(2026, 11)

    @Before fun setUp() {
        database = FinanceGenieDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext, storageClock)
        repository = RoomLedgerRepository(database, storageClock)
        runBlocking { repository.saveCategory(LedgerCategory("food", "Food")) }
    }
    @After fun tearDown() { database.close() }

    @Test fun copyCreatesFreshRecordsAtomicallyAndPreservesSourceAndExistingTarget() = runBlocking {
        val overall = budget(200, 500_000)
        val food = budget(201, 300_000, "food")
        val target = budget(202, 900_000, "food", november.plusMonths(1))
        listOf(overall, food, target).forEach { repository.saveBudget(it) }
        val before = repository.read()
        val result = repository.copyBudgetsToNextMonth(november, before.settings.revision)
        val after = repository.read()
        assertEquals(1, result.createdCount)
        assertEquals(1, result.existingCount)
        assertEquals(before.settings.revision + 1, after.settings.revision)
        before.budgets.forEach { previous -> assertEquals(previous, after.budgets.first { it.value.id == previous.value.id }) }
        val copied = after.budgets.single { it.value.month == november.plusMonths(1) && it.value.categoryId == null }
        assertEquals(500_000L, copied.value.limitMinor)
        assertNotEquals(overall.id, copied.value.id)
        assertEquals(1L, copied.metadata.revision)
    }

    @Test fun failureDuringSecondCopyRollsBackAllRowsAndLedgerRevision() = runBlocking {
        repository.saveBudget(budget(200, 500_000))
        repository.saveBudget(budget(201, 300_000, "food"))
        val before = repository.read()
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_test_budget_copy BEFORE INSERT ON budgets
            WHEN NEW.month = '2026-12' AND NEW.category_id = 'food'
            BEGIN SELECT RAISE(ABORT, 'Synthetic copy failure'); END
        """.trimIndent())
        fails<android.database.sqlite.SQLiteConstraintException> { repository.copyBudgetsToNextMonth(november, before.settings.revision) }
        assertEquals(before, repository.read())
    }

    @Test fun staleCopyCannotApplyDifferentLimitsThanThePreview() = runBlocking {
        val original = budget(200, 500_000)
        repository.saveBudget(original)
        val preview = repository.read()
        repository.saveBudget(original.copy(limitMinor = 800_000), 1)
        val beforeFailure = repository.read()
        fails<StaleRecordException> { repository.copyBudgetsToNextMonth(november, preview.settings.revision) }
        assertEquals(beforeFailure, repository.read())
    }

    @Test fun archivedCopyAndRepeatedCopyAreSafeNoOps() = runBlocking {
        repository.saveBudget(budget(200, 0))
        repository.saveBudget(budget(201, 300_000, "food"))
        repository.saveCategory(LedgerCategory("food", "Food", true), 1)
        val initial = repository.read()
        val first = repository.copyBudgetsToNextMonth(november, initial.settings.revision)
        assertEquals(1, first.createdCount)
        assertEquals(1, first.archivedCount)
        val copied = repository.read()
        val second = repository.copyBudgetsToNextMonth(november, copied.settings.revision)
        assertEquals(0, second.createdCount)
        assertEquals(1, second.existingCount)
        assertEquals(copied, repository.read())
    }

    @Test fun removeIsRevisionCheckedAndDoesNotEraseTransactionsOrOtherMonths() = runBlocking {
        repository.saveAccount(storageAccounts()[0])
        repository.applyTransactions(listOf(TransactionWrite(storageExpense())))
        val original = budget(200, 0)
        val otherMonth = budget(201, 100_000, month = november.plusMonths(1))
        repository.saveBudget(original)
        repository.saveBudget(otherMonth)
        repository.saveBudget(original.copy(limitMinor = 500_000), 1)
        val before = repository.read()
        fails<StaleRecordException> { repository.removeBudget(original.id, 1) }
        assertEquals(before, repository.read())
        repository.removeBudget(original.id, 2)
        val after = repository.read()
        assertEquals(before.transactions, after.transactions)
        assertEquals(listOf(otherMonth), after.budgets.map { it.value })
        fails<StaleRecordException> { repository.removeBudget(original.id, 2) }
        assertEquals(after, repository.read())
    }

    @Test fun anEditCannotMoveAnExistingBudgetIntoAnotherScopeOrMonth() = runBlocking {
        val original = budget(200, 500_000)
        repository.saveBudget(original)
        val before = repository.read()
        fails<IllegalArgumentException> { repository.saveBudget(original.copy(month = november.plusMonths(1)), 1) }
        fails<IllegalArgumentException> { repository.saveBudget(original.copy(categoryId = "food"), 1) }
        assertEquals(before, repository.read())
        assertTrue(before.transactions.isEmpty())
    }

    private fun budget(id: Long, limit: Long, category: String? = null, month: YearMonth = november) = MonthlyBudget(UUID(0, id), month, limit, category)
    private suspend inline fun <reified T : Exception> fails(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
