package com.financegenie.android.domain

import java.time.YearMonth

/** Original limits to copy; new record IDs are assigned only at the storage boundary. */
data class BudgetCopyPlan(
    val fromMonth: YearMonth,
    val toCopy: List<MonthlyBudget>,
    val existingCount: Int,
    val archivedCount: Int,
) {
    val toMonth: YearMonth = fromMonth.plusMonths(1)
}

fun planBudgetCopy(snapshot: LedgerSnapshot, fromMonth: YearMonth): BudgetCopyPlan {
    val target = fromMonth.plusMonths(1)
    require(target.year in 1..9999) { "Budget month is outside the supported range" }
    val existing = snapshot.budgets.filter { it.month == target }.map { it.categoryId }.toSet()
    val categories = snapshot.categories.associateBy { it.id }
    var existingCount = 0
    var archivedCount = 0
    val toCopy = snapshot.budgets.filter { it.month == fromMonth }.sortedBy { it.categoryId.orEmpty() }.filter {
        when {
            it.categoryId in existing -> { existingCount++; false }
            it.categoryId != null && categories.getValue(it.categoryId).isArchived -> { archivedCount++; false }
            else -> true
        }
    }
    return BudgetCopyPlan(fromMonth, toCopy, existingCount, archivedCount)
}
