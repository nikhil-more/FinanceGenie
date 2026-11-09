package com.financegenie.android.domain

import java.math.BigInteger
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class LedgerReport(
    val range: DateRange,
    val balancesAsOfDate: LocalDate,
    val accountPositionsMinor: Map<UUID, Long>,
    val grossSpendingMinor: Long,
    val refundsMinor: Long,
    val incomeMinor: Long,
    val adjustmentsMinor: Long,
    val categoryNetSpendingMinor: Map<String, Long>,
    val dailyNetSpendingMinor: Map<LocalDate, Long>,
    val monthlyNetSpendingMinor: Map<YearMonth, Long>,
) {
    val netSpendingMinor: Long = Money.subtract(grossSpendingMinor, refundsMinor)
    val recordedSurplusMinor: Long = Money.subtract(incomeMinor, netSpendingMinor)
    val totalTrackedPositionMinor: Long = Money.sum(accountPositionsMinor.values)
}

data class BudgetProgress(val budget: MonthlyBudget, val netSpendingMinor: Long) {
    val remainingMinor: Long = Money.subtract(budget.limitMinor, netSpendingMinor)
    val isOverspent: Boolean = remainingMinor < 0L
}

data class BalanceReconciliation(val observation: BalanceObservation, val estimatedBalanceMinor: Long) {
    val differenceMinor: Long = Money.subtract(observation.reportedBalanceMinor, estimatedBalanceMinor)
}

class LedgerCalculator(private val clock: Clock = Clock.systemUTC()) {
    private val validator = LedgerValidator(clock)

    fun monthlyReport(snapshot: LedgerSnapshot, month: YearMonth): LedgerReport = report(
        snapshot,
        DateRange.month(month),
    )

    fun report(snapshot: LedgerSnapshot, range: DateRange): LedgerReport {
        validator.validate(snapshot)
        val asOfDate = minOf(range.endExclusive.minusDays(1), LedgerDates.today(clock))
        val active = snapshot.transactions.filterNot { it.isDeleted }
        val period = active.filter { it.effectiveDate in range }
        val categories = mutableMapOf<String, BigInteger>()
        val days = mutableMapOf<LocalDate, BigInteger>()
        val months = mutableMapOf<YearMonth, BigInteger>()
        period.forEach { transaction ->
            val netAmount = when (transaction.kind) {
                TransactionKind.EXPENSE -> transaction.amountMinor
                TransactionKind.REFUND -> -transaction.amountMinor
                else -> return@forEach
            }
            categories.add(requireNotNull(transaction.spendingCategoryId), netAmount)
            days.add(transaction.effectiveDate, netAmount)
            months.add(YearMonth.from(transaction.effectiveDate), netAmount)
        }
        return LedgerReport(
            range = range,
            balancesAsOfDate = asOfDate,
            accountPositionsMinor = positions(snapshot, active, asOfDate),
            grossSpendingMinor = Money.sum(period.filter { it.kind == TransactionKind.EXPENSE }.map { it.amountMinor }),
            refundsMinor = Money.sum(period.filter { it.kind == TransactionKind.REFUND }.map { it.amountMinor }),
            incomeMinor = Money.sum(period.filter { it.kind == TransactionKind.INCOME }.map { it.amountMinor }),
            adjustmentsMinor = Money.sum(period.filter { it.kind == TransactionKind.ADJUSTMENT }.map { adjustmentEffect(it) }),
            categoryNetSpendingMinor = categories.toSortedMap().mapValues { Money.checkedTotal(it.value) },
            dailyNetSpendingMinor = days.toSortedMap().mapValues { Money.checkedTotal(it.value) },
            monthlyNetSpendingMinor = months.toSortedMap().mapValues { Money.checkedTotal(it.value) },
        )
    }

    fun budgetProgress(budget: MonthlyBudget, report: LedgerReport): BudgetProgress {
        require(report.range == DateRange.month(budget.month)) { "Budget and report periods must match" }
        val spending = budget.categoryId?.let { report.categoryNetSpendingMinor[it] ?: 0L }
            ?: report.netSpendingMinor
        return BudgetProgress(budget, spending)
    }

    fun reconcile(snapshot: LedgerSnapshot, observationId: UUID): BalanceReconciliation {
        validator.validate(snapshot)
        val observation = requireNotNull(snapshot.observations.find { it.id == observationId }) {
            "Balance observation does not exist"
        }
        val positions = positions(snapshot, snapshot.transactions.filterNot { it.isDeleted }, observation.asOfDate)
        return BalanceReconciliation(observation, positions.getValue(observation.accountId))
    }

    private fun positions(
        snapshot: LedgerSnapshot,
        active: List<LedgerTransaction>,
        throughDate: LocalDate,
    ): Map<UUID, Long> {
        val balances = snapshot.accounts.filter { it.openingDate <= throughDate }
            .associate { it.id to BigInteger.valueOf(it.openingBalanceMinor) }.toMutableMap()
        active.filter { it.effectiveDate <= throughDate }.forEach { transaction ->
            when (transaction.kind) {
                TransactionKind.EXPENSE -> balances.add(transaction.accountId, -transaction.amountMinor)
                TransactionKind.INCOME, TransactionKind.REFUND -> balances.add(transaction.accountId, transaction.amountMinor)
                TransactionKind.ADJUSTMENT -> balances.add(transaction.accountId, adjustmentEffect(transaction))
                TransactionKind.TRANSFER -> {
                    balances.add(transaction.accountId, -transaction.amountMinor)
                    balances.add(requireNotNull(transaction.destinationAccountId), transaction.amountMinor)
                }
            }
        }
        return balances.mapValues { Money.checkedTotal(it.value) }
    }

    private fun adjustmentEffect(transaction: LedgerTransaction): Long = when (transaction.adjustmentDirection) {
        AdjustmentDirection.INCREASE -> transaction.amountMinor
        AdjustmentDirection.DECREASE -> -transaction.amountMinor
        null -> error("Adjustment direction is required")
    }

    private fun <K> MutableMap<K, BigInteger>.add(key: K, minor: Long) {
        this[key] = getOrDefault(key, BigInteger.ZERO).add(BigInteger.valueOf(minor))
    }
}
