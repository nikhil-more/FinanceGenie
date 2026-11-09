package com.financegenie.android.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCalculatorTest {
    private val calculator = LedgerCalculator(testClock)

    @Test
    fun expenseAndPartialRefundRestoreBudgetExactly() {
        val expense = transaction()
        val refund = transaction(101, TransactionKind.REFUND, 10_000, originalExpense = expense.id)
        val report = calculator.monthlyReport(ledger(expense, refund), november)
        assertEquals(35_000L, report.grossSpendingMinor)
        assertEquals(10_000L, report.refundsMinor)
        assertEquals(25_000L, report.netSpendingMinor)
        assertEquals(75_000L, calculator.budgetProgress(foodBudget(), report).remainingMinor)
        assertEquals(-25_000L, report.accountPositionsMinor.getValue(bankId))
    }

    @Test
    fun ownAccountTransferHasNoSpendingIncomeOrPositionEffect() {
        val transfer = transaction(kind = TransactionKind.TRANSFER, amount = 50_000, destination = cashId)
        val report = calculator.monthlyReport(ledger(transfer), november)
        assertEquals(0L, report.netSpendingMinor)
        assertEquals(0L, report.incomeMinor)
        assertEquals(0L, report.totalTrackedPositionMinor)
        assertEquals(-50_000L, report.accountPositionsMinor.getValue(bankId))
        assertEquals(50_000L, report.accountPositionsMinor.getValue(cashId))
    }

    @Test
    fun cardPurchaseAndPaymentCountExpenseOnceAndSupportOverpayment() {
        val purchase = transaction(account = cardId, amount = 149_900)
        val payment = transaction(101, TransactionKind.TRANSFER, 150_000, destination = cardId)
        val report = calculator.monthlyReport(ledger(purchase, payment), november)
        assertEquals(149_900L, report.netSpendingMinor)
        assertEquals(100L, report.accountPositionsMinor.getValue(cardId))
        assertEquals(-150_000L, report.accountPositionsMinor.getValue(bankId))
    }

    @Test
    fun refundInLaterMonthDoesNotRewritePurchaseMonth() {
        val expense = transaction(amount = 10_000)
        val refund = transaction(101, TransactionKind.REFUND, 10_000, date = LocalDate.of(2026, 12, 1), originalExpense = expense.id)
        val snapshot = ledger(expense, refund)
        assertEquals(10_000L, calculator.monthlyReport(snapshot, november).netSpendingMinor)
        val december = YearMonth.of(2026, 12)
        val decemberReport = calculator.monthlyReport(snapshot, december)
        assertEquals(-10_000L, decemberReport.netSpendingMinor)
        assertEquals(110_000L, calculator.budgetProgress(foodBudget(month = december), decemberReport).remainingMinor)
        assertEquals(-10_000L, decemberReport.categoryNetSpendingMinor.getValue("food"))
    }

    @Test
    fun zeroBudgetAndOverspendingRemainDistinctFromNoBudget() {
        val report = calculator.monthlyReport(ledger(transaction(amount = 550_000)), november)
        assertEquals(-50_000L, calculator.budgetProgress(foodBudget(500_000), report).remainingMinor)
        assertTrue(calculator.budgetProgress(foodBudget(500_000), report).isOverspent)
        assertEquals(-550_000L, calculator.budgetProgress(foodBudget(0), report).remainingMinor)
        assertTrue(ledger().budgets.isEmpty())
    }

    @Test
    fun overallAndCategoryCapsApplyIndependently() {
        val report = calculator.monthlyReport(ledger(transaction()), november)
        val overall = MonthlyBudget(UUID(0, 201), november, 100_000)
        assertEquals(65_000L, calculator.budgetProgress(overall, report).remainingMinor)
        assertEquals(65_000L, calculator.budgetProgress(foodBudget(), report).remainingMinor)
        assertThrows(IllegalArgumentException::class.java) {
            calculator.budgetProgress(foodBudget(month = november.plusMonths(1)), report)
        }
    }

    @Test
    fun adjustmentsAffectPositionsButNotSpendingOrIncome() {
        val increase = transaction(kind = TransactionKind.ADJUSTMENT, amount = 5_000, direction = AdjustmentDirection.INCREASE, reason = "Synthetic correction")
        val decrease = transaction(101, TransactionKind.ADJUSTMENT, 2_000, direction = AdjustmentDirection.DECREASE, reason = "Synthetic correction")
        val report = calculator.monthlyReport(ledger(increase, decrease), november)
        assertEquals(3_000L, report.adjustmentsMinor)
        assertEquals(3_000L, report.totalTrackedPositionMinor)
        assertEquals(0L, report.incomeMinor)
        assertEquals(0L, report.netSpendingMinor)
    }

    @Test
    fun deletionUndoAndBackdatedEditsRecalculateWithoutDuplicates() {
        val expense = transaction()
        val snapshot = ledger(expense)
        val before = calculator.monthlyReport(snapshot, november)
        assertEquals(0L, calculator.monthlyReport(ledger(expense.copy(isDeleted = true)), november).netSpendingMinor)
        assertEquals(before, calculator.monthlyReport(ledger(expense.copy(isDeleted = false)), november))
        val moved = ledger(expense.copy(effectiveDate = LocalDate.of(2026, 12, 1)))
        assertEquals(0L, calculator.monthlyReport(moved, november).netSpendingMinor)
        assertEquals(35_000L, calculator.monthlyReport(moved, YearMonth.of(2026, 12)).netSpendingMinor)
    }

    @Test
    fun missingCategoryMapsToUncategorizedAndArchivedHistoryRemainsIncluded() {
        val snapshot = ledger(transaction(category = null)).let { original ->
            original.copy(accounts = original.accounts.map { it.copy(isArchived = true) })
        }
        val report = calculator.monthlyReport(snapshot, november)
        assertEquals(35_000L, report.categoryNetSpendingMinor.getValue(UNCATEGORIZED_ID))
        assertEquals(-35_000L, report.totalTrackedPositionMinor)
    }

    @Test
    fun openingBalancesAreNotIncomeAndLaterOpeningsAreNotInventedInHistory() {
        val snapshot = ledger().copy(accounts = listOf(
            accounts()[0].copy(openingBalanceMinor = 90_000),
            accounts()[1].copy(openingDate = LocalDate.of(2026, 12, 1), openingBalanceMinor = 10_000),
        ))
        val report = calculator.monthlyReport(snapshot, november)
        assertEquals(90_000L, report.totalTrackedPositionMinor)
        assertEquals(0L, report.incomeMinor)
        assertFalse(report.accountPositionsMinor.containsKey(cashId))
    }

    @Test
    fun statementObservationIsEndOfDayAndNeverChangesLedgerPosition() {
        val observation = BalanceObservation(UUID(0, 300), bankId, LocalDate.of(2026, 11, 2), -30_000)
        val snapshot = ledger(transaction()).copy(observations = listOf(observation))
        val reconciliation = calculator.reconcile(snapshot, observation.id)
        assertEquals(-35_000L, reconciliation.estimatedBalanceMinor)
        assertEquals(5_000L, reconciliation.differenceMinor)
        assertEquals(-35_000L, calculator.monthlyReport(snapshot, november).totalTrackedPositionMinor)
    }

    @Test
    fun leapDayAndHalfOpenMonthBoundariesAreExact() {
        val snapshot = ledger(
            transaction(amount = 1, date = LocalDate.of(2024, 2, 29)),
            transaction(101, amount = 2, date = LocalDate.of(2024, 3, 1)),
        )
        val report = calculator.monthlyReport(snapshot, YearMonth.of(2024, 2))
        assertEquals(1L, report.netSpendingMinor)
        assertEquals(mapOf(LocalDate.of(2024, 2, 29) to 1L), report.dailyNetSpendingMinor)
        assertEquals(mapOf(YearMonth.of(2024, 2) to 1L), report.monthlyNetSpendingMinor)
    }

    @Test
    fun indiaDateAndTodayIgnoreClockDisplayZone() {
        val instant = Instant.parse("2026-10-31T18:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 1), LedgerDates.effectiveDate(instant))
        val clock = Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDate.of(2026, 11, 1), LedgerDates.today(clock))
        val timed = transaction(date = LedgerDates.effectiveDate(instant)).copy(occurredAt = instant, originalZoneId = "UTC")
        assertEquals(35_000L, LedgerCalculator(clock).monthlyReport(ledger(timed), november).netSpendingMinor)
        assertEquals(35_000L, LedgerCalculator(clock.withZone(ZoneOffset.ofHours(9))).monthlyReport(ledger(timed), november).netSpendingMinor)
    }

    @Test
    fun futureReportDoesNotLabelCurrentBalanceAsAFutureBankBalance() {
        val report = calculator.monthlyReport(ledger(transaction()), YearMonth.of(2027, 1))
        assertEquals(LocalDate.of(2026, 12, 31), report.balancesAsOfDate)
        assertEquals(0L, report.netSpendingMinor)
    }

    @Test
    fun emptyLedgerHasExactZeroMetrics() {
        val report = calculator.monthlyReport(LedgerSnapshot(), november)
        assertEquals(0L, report.netSpendingMinor)
        assertEquals(0L, report.totalTrackedPositionMinor)
        assertTrue(report.categoryNetSpendingMinor.isEmpty())
    }
}
