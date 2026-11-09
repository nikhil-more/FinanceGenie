package com.financegenie.android.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Selection affects activity metrics, never validation or the underlying account positions. */
data class TransactionSelection(
    val accountId: UUID? = null,
    val categoryId: String? = null,
    val includedIds: Set<UUID>? = null,
) {
    fun includes(event: LedgerTransaction): Boolean =
        (accountId == null || event.accountId == accountId || event.destinationAccountId == accountId) &&
            (categoryId == null || event.spendingCategoryId == categoryId) &&
            (includedIds == null || event.id in includedIds)
}

data class ReportRequest(val range: DateRange, val selection: TransactionSelection = TransactionSelection())
data class CategorySpending(val category: LedgerCategory, val netSpendingMinor: Long)
data class DailySpending(val date: LocalDate, val netSpendingMinor: Long)
data class MonthlySpending(val month: YearMonth, val netSpendingMinor: Long)

data class AnalyticsWindow(val period: DateRange, val daily: DateRange, val monthly: DateRange) {
    companion object {
        fun forSelection(snapshot: LedgerSnapshot, month: YearMonth, allDates: Boolean, today: LocalDate): AnalyticsWindow {
            val lastMonth = if (allDates) YearMonth.from(today) else month
            val earliest = snapshot.transactions.filterNot { it.isDeleted }.minOfOrNull { it.effectiveDate } ?: today
            return AnalyticsWindow(
                period = if (allDates) DateRange(minOf(earliest, today), today.plusDays(1)) else DateRange.month(month),
                daily = if (allDates) DateRange(today.minusDays(29), today.plusDays(1)) else DateRange.month(month),
                monthly = DateRange(maxOf(lastMonth.minusMonths(5), YearMonth.of(1, 1)).atDay(1), lastMonth.plusMonths(1).atDay(1)),
            )
        }
    }
}

data class LedgerAnalytics(
    val report: LedgerReport,
    val dailyRange: DateRange,
    val monthlyRange: DateRange,
    val categories: List<CategorySpending>,
    val days: List<DailySpending>,
    val months: List<MonthlySpending>,
)

fun buildAnalytics(snapshot: LedgerSnapshot, report: LedgerReport, daily: LedgerReport, monthly: LedgerReport): LedgerAnalytics {
    val dayCount = ChronoUnit.DAYS.between(daily.range.startInclusive, daily.range.endExclusive)
    val firstMonth = YearMonth.from(monthly.range.startInclusive)
    val monthCount = ChronoUnit.MONTHS.between(firstMonth, YearMonth.from(monthly.range.endExclusive))
    require(dayCount in 1..31 && monthCount in 1..6) { "Analytics series must be bounded" }
    val categories = snapshot.categories.associateBy { it.id }
    return LedgerAnalytics(
        report, daily.range, monthly.range,
        report.categoryNetSpendingMinor.map { (id, amount) -> CategorySpending(categories.getValue(id), amount) }
            .sortedWith(compareByDescending<CategorySpending> { it.netSpendingMinor }.thenBy { it.category.id }),
        (0 until dayCount).map { offset ->
            val date = daily.range.startInclusive.plusDays(offset)
            DailySpending(date, daily.dailyNetSpendingMinor[date] ?: 0)
        },
        (0 until monthCount).map { offset ->
            val month = firstMonth.plusMonths(offset)
            MonthlySpending(month, monthly.monthlyNetSpendingMinor[month] ?: 0)
        },
    )
}
