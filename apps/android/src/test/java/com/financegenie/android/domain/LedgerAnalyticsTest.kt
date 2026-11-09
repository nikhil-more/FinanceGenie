package com.financegenie.android.domain

import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerAnalyticsTest {
    private val calculator = LedgerCalculator(testClock)

    @Test fun accountSelectionIncludesBothTransferSidesButNeverMakesTransfersIncome() {
        val snapshot = ledger(transaction(amount = 400_000), transaction(101, TransactionKind.TRANSFER, 100_000, destination = cashId),
            transaction(102, TransactionKind.INCOME, 600_000, category = null))
        val bank = calculator.report(snapshot, DateRange.month(november), TransactionSelection(accountId = bankId))
        val cash = calculator.report(snapshot, DateRange.month(november), TransactionSelection(accountId = cashId))
        assertEquals(400_000L, bank.netSpendingMinor)
        assertEquals(600_000L, bank.incomeMinor)
        assertEquals(0L, cash.netSpendingMinor)
        assertEquals(0L, cash.incomeMinor)
        assertEquals(bank.accountPositionsMinor, cash.accountPositionsMinor)
        assertTrue(TransactionSelection(accountId = cashId).includes(snapshot.transactions[1]))
    }

    @Test fun selectedRefundValidatesAgainstItsOutOfScopeOriginalPurchase() {
        val purchase = transaction(amount = 10_000)
        val refund = transaction(101, TransactionKind.REFUND, 2_500, cashId, LocalDate.of(2026, 12, 1), originalExpense = purchase.id)
        val snapshot = ledger(purchase, refund)
        val report = calculator.report(snapshot, DateRange.month(YearMonth.of(2026, 12)),
            TransactionSelection(accountId = cashId, includedIds = setOf(refund.id)))
        assertEquals(-2_500L, report.netSpendingMinor)
        assertEquals(2_500L, report.refundsMinor)
        assertEquals(-2_500L, report.categoryNetSpendingMinor.getValue("food"))
    }

    @Test fun categoryAndSearchIdsIntersectAndNormalizeUncategorized() {
        val uncategorized = transaction(category = null, amount = 1)
        val food = transaction(101, amount = 200)
        val snapshot = ledger(uncategorized, food)
        val report = calculator.report(snapshot, DateRange.month(november),
            TransactionSelection(categoryId = UNCATEGORIZED_ID, includedIds = setOf(uncategorized.id, food.id)))
        assertEquals(1L, report.netSpendingMinor)
        assertEquals(mapOf(UNCATEGORIZED_ID to 1L), report.categoryNetSpendingMinor)
        assertEquals(0L, calculator.report(snapshot, DateRange.month(november), TransactionSelection(includedIds = emptySet())).netSpendingMinor)
    }

    @Test fun deletedRecordsNeverEnterMetricsEvenWhenTheirIdsAreSelected() {
        val deleted = transaction().copy(isDeleted = true)
        val report = calculator.report(ledger(deleted), DateRange.month(november), TransactionSelection(includedIds = setOf(deleted.id)))
        assertEquals(0L, report.netSpendingMinor)
        assertTrue(report.categoryNetSpendingMinor.isEmpty())
    }

    @Test fun emptyAnalyticsHaveBoundedZeroFilledCalendarSeries() {
        val analytics = analyze(ledger())
        assertTrue(analytics.categories.isEmpty())
        assertEquals(30, analytics.days.size)
        assertEquals(6, analytics.months.size)
        assertTrue(analytics.days.all { it.netSpendingMinor == 0L })
        assertTrue(analytics.months.all { it.netSpendingMinor == 0L })
    }

    @Test fun oneRecordAndRefundHeavyCategoryKeepExactSignedAmounts() {
        val refund = transaction(kind = TransactionKind.REFUND, amount = 1, confirmed = true)
        val analytics = analyze(ledger(refund))
        assertEquals(-1L, analytics.categories.single().netSpendingMinor)
        assertEquals(-1L, analytics.days.single { it.date == refund.effectiveDate }.netSpendingMinor)
        assertEquals(-1L, analytics.months.last().netSpendingMinor)
    }

    @Test fun thousandRowsAgreeAcrossSummaryCategoryDailyAndMonthlyTotals() {
        val events = (1..1_000).map { index -> transaction(index.toLong(), amount = 1,
            date = november.atDay(index % 28 + 1)) }
        val analytics = analyze(ledger(*events.toTypedArray()))
        assertEquals(1_000L, analytics.report.netSpendingMinor)
        assertEquals(1_000L, Money.sum(analytics.categories.map { it.netSpendingMinor }))
        assertEquals(1_000L, Money.sum(analytics.days.map { it.netSpendingMinor }))
        assertEquals(1_000L, analytics.months.last().netSpendingMinor)
    }

    @Test fun allDatesTotalsUseFullHistoryWhileChartsStayBounded() {
        val snapshot = ledger(transaction(date = LocalDate.of(2024, 1, 1)))
        val window = AnalyticsWindow.forSelection(snapshot, november, true, LocalDate.of(2026, 12, 31))
        assertEquals(LocalDate.of(2024, 1, 1), window.period.startInclusive)
        assertEquals(LocalDate.of(2027, 1, 1), window.period.endExclusive)
        assertEquals(LocalDate.of(2026, 12, 2), window.daily.startInclusive)
        assertEquals(LocalDate.of(2026, 7, 1), window.monthly.startInclusive)
    }

    @Test fun leapMonthAndEarliestSupportedMonthDoNotInventBuckets() {
        val leap = analyze(ledger(), YearMonth.of(2024, 2))
        assertEquals(29, leap.days.size)
        assertEquals(LocalDate.of(2024, 2, 29), leap.days.last().date)
        assertEquals(1, analyze(ledger(), YearMonth.of(1, 1)).months.size)
    }

    @Test fun archivedLongCategoryLabelsAndBackdatedEditsRemainConsistent() {
        val original = transaction()
        val snapshot = ledger(original).copy(categories = listOf(LedgerCategory.uncategorized(), LedgerCategory("food", "Long synthetic category name repeated for accessibility and wrapping", true)))
        assertTrue(analyze(snapshot).categories.single().category.isArchived)
        val changed = snapshot.copy(transactions = listOf(original.copy(effectiveDate = LocalDate.of(2026, 12, 1), amountMinor = 1)))
        assertEquals(0L, analyze(changed).report.netSpendingMinor)
        assertEquals(1L, analyze(changed, november.plusMonths(1)).report.netSpendingMinor)
    }

    private fun analyze(snapshot: LedgerSnapshot, month: YearMonth = november): LedgerAnalytics {
        val window = AnalyticsWindow.forSelection(snapshot, month, false, LedgerDates.today(testClock))
        val reports = calculator.reports(snapshot, listOf(window.period, window.daily, window.monthly).map { ReportRequest(it) })
        return buildAnalytics(snapshot, reports[0], reports[1], reports[2])
    }
}
