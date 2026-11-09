package com.financegenie.android.domain

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetPlanningTest {
    private val calculator = LedgerCalculator(testClock)

    @Test fun remainingAndOverspendingHaveExactWarningBoundaries() {
        val budget = foodBudget(500_000)
        val within = BudgetProgress(budget, 399_999, 0)
        assertEquals(BudgetWarning.NONE, within.warning)
        val near = BudgetProgress(budget, 400_000, 0)
        assertEquals(100_000L, near.remainingMinor)
        assertEquals(BudgetWarning.NEAR_LIMIT, near.warning)
        assertEquals(BudgetWarning.AT_LIMIT, BudgetProgress(budget, 500_000, 0).warning)
        val over = BudgetProgress(budget, 550_000, 0)
        assertEquals(-50_000L, over.remainingMinor)
        assertEquals(BudgetWarning.OVER_LIMIT, over.warning)
    }

    @Test fun zeroLimitIsRealAndRefundsCanLeaveMoreThanTheLimit() {
        val zero = foodBudget(0)
        assertEquals(BudgetWarning.AT_LIMIT, BudgetProgress(zero, 0, 0).warning)
        assertEquals(BudgetWarning.OVER_LIMIT, BudgetProgress(zero, 1, 0).warning)
        val refund = BudgetProgress(zero, 0, 1)
        assertEquals(-1L, refund.netSpendingMinor)
        assertEquals(1L, refund.remainingMinor)
        assertEquals(BudgetWarning.NONE, refund.warning)
        assertTrue(ledger().budgets.isEmpty())
    }

    @Test fun laterRefundExposesGrossRefundAndNegativeNetWithoutRewritingHistory() {
        val expense = transaction(amount = 400_000)
        val refund = transaction(101, TransactionKind.REFUND, 100_000, date = LocalDate.of(2026, 12, 1), originalExpense = expense.id)
        val snapshot = ledger(expense, refund)
        val first = calculator.budgetProgress(foodBudget(500_000), calculator.monthlyReport(snapshot, november))
        assertEquals(400_000L, first.grossSpendingMinor)
        assertEquals(0L, first.refundsMinor)
        assertEquals(100_000L, first.remainingMinor)
        val next = november.plusMonths(1)
        val later = calculator.budgetProgress(foodBudget(500_000, next), calculator.monthlyReport(snapshot, next))
        assertEquals(0L, later.grossSpendingMinor)
        assertEquals(100_000L, later.refundsMinor)
        assertEquals(-100_000L, later.netSpendingMinor)
        assertEquals(600_000L, later.remainingMinor)
        assertEquals(BudgetWarning.NONE, later.warning)
    }

    @Test fun overallAndCategoryReportsExcludeTransfersIncomeAndAdjustments() {
        val snapshot = ledger(
            transaction(amount = 400_000),
            transaction(101, amount = 50_000, category = UNCATEGORIZED_ID),
            transaction(102, TransactionKind.TRANSFER, 100_000, destination = cashId),
            transaction(103, TransactionKind.INCOME, 600_000),
            transaction(104, TransactionKind.ADJUSTMENT, 10_000, direction = AdjustmentDirection.INCREASE, reason = "Synthetic correction"),
        )
        val report = calculator.monthlyReport(snapshot, november)
        assertEquals(100_000L, calculator.budgetProgress(foodBudget(500_000), report).remainingMinor)
        assertEquals(550_000L, calculator.budgetProgress(MonthlyBudget(UUID(0, 201), november, 1_000_000), report).remainingMinor)
    }

    @Test fun backdatedEditMovesSpendingBetweenMonths() {
        val expense = transaction(amount = 400_000)
        val next = november.plusMonths(1)
        val changed = ledger(expense.copy(effectiveDate = next.atDay(1), amountMinor = 550_000))
        assertEquals(500_000L, calculator.budgetProgress(foodBudget(500_000), calculator.monthlyReport(changed, november)).remainingMinor)
        assertEquals(-50_000L, calculator.budgetProgress(foodBudget(500_000, next), calculator.monthlyReport(changed, next)).remainingMinor)
    }

    @Test fun refundCanClearAnExistingWarning() {
        val budget = foodBudget(500_000)
        assertEquals(BudgetWarning.OVER_LIMIT, BudgetProgress(budget, 550_000, 0).warning)
        assertEquals(BudgetWarning.NEAR_LIMIT, BudgetProgress(budget, 550_000, 100_000).warning)
        assertEquals(BudgetWarning.NONE, BudgetProgress(budget, 550_000, 200_001).warning)
    }

    @Test fun copyingPreservesExistingTargetAndOriginalSourceValues() {
        val overall = MonthlyBudget(UUID(0, 201), november, 500_000)
        val source = foodBudget(300_000)
        val existing = source.copy(id = UUID(0, 202), month = november.plusMonths(1), limitMinor = 900_000)
        val snapshot = ledger().copy(budgets = listOf(source, overall, existing))
        val plan = planBudgetCopy(snapshot, november)
        assertEquals(listOf(overall), plan.toCopy)
        assertEquals(1, plan.existingCount)
        assertEquals(0, plan.archivedCount)
        assertEquals(listOf(source, overall, existing), snapshot.budgets)
        assertEquals(november.plusMonths(1), plan.toMonth)
    }

    @Test fun archivedCategoryLimitsAreVisibleHistoryButNotNewCopyCandidates() {
        val snapshot = ledger().copy(categories = listOf(LedgerCategory.uncategorized(), LedgerCategory("food", "Food", true)),
            budgets = listOf(foodBudget()))
        val plan = planBudgetCopy(snapshot, november)
        assertTrue(plan.toCopy.isEmpty())
        assertEquals(1, plan.archivedCount)
        assertEquals(0, plan.existingCount)
    }

    @Test fun decemberCopyTargetsJanuaryAndNeverReusesOlderMonths() {
        val december = YearMonth.of(2026, 12)
        val source = foodBudget(0, december)
        val snapshot = ledger().copy(budgets = listOf(source, foodBudget().copy(id = UUID(0, 201))))
        val plan = planBudgetCopy(snapshot, december)
        assertEquals(YearMonth.of(2027, 1), plan.toMonth)
        assertEquals(listOf(source), plan.toCopy)
        assertTrue(planBudgetCopy(snapshot, YearMonth.of(2027, 1)).toCopy.isEmpty())
    }

    @Test fun fullyPopulatedTargetProducesNoOverwriteCandidates() {
        val source = foodBudget(0)
        val target = source.copy(id = UUID(0, 201), month = november.plusMonths(1), limitMinor = 1)
        val plan = planBudgetCopy(ledger().copy(budgets = listOf(source, target)), november)
        assertTrue(plan.toCopy.isEmpty())
        assertEquals(1, plan.existingCount)
        assertFalse(plan.toCopy.any { it.id == target.id })
    }
}
