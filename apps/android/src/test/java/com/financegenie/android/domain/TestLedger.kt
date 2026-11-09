package com.financegenie.android.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

internal val testClock: Clock = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneOffset.UTC)
internal val november: YearMonth = YearMonth.of(2026, 11)
internal val bankId: UUID = UUID(0, 1)
internal val cashId: UUID = UUID(0, 2)
internal val cardId: UUID = UUID(0, 3)

internal fun accounts(): List<LedgerAccount> = listOf(
    LedgerAccount(bankId, "Synthetic Bank", AccountKind.BANK, LocalDate.of(2024, 1, 1)),
    LedgerAccount(cashId, "Synthetic Cash", AccountKind.CASH, LocalDate.of(2024, 1, 1)),
    LedgerAccount(cardId, "Synthetic Card", AccountKind.CREDIT_CARD, LocalDate.of(2024, 1, 1)),
)

internal fun transaction(
    number: Long = 100,
    kind: TransactionKind = TransactionKind.EXPENSE,
    amount: Long = 35_000,
    account: UUID = bankId,
    date: LocalDate = LocalDate.of(2026, 11, 2),
    category: String? = "food",
    destination: UUID? = null,
    originalExpense: UUID? = null,
    confirmed: Boolean = false,
    direction: AdjustmentDirection? = null,
    reason: String? = null,
): LedgerTransaction = LedgerTransaction(
    id = UUID(0, number),
    kind = kind,
    amountMinor = amount,
    accountId = account,
    effectiveDate = date,
    categoryId = category,
    destinationAccountId = destination,
    originalExpenseId = originalExpense,
    unlinkedRefundConfirmed = confirmed,
    adjustmentDirection = direction,
    adjustmentReason = reason,
)

internal fun ledger(vararg transactions: LedgerTransaction): LedgerSnapshot = LedgerSnapshot(
    accounts = accounts(),
    categories = listOf(LedgerCategory.uncategorized(), LedgerCategory("food", "Food")),
    transactions = transactions.toList(),
)

internal fun foodBudget(limit: Long = 100_000, month: YearMonth = november): MonthlyBudget = MonthlyBudget(
    id = UUID(0, 200),
    month = month,
    limitMinor = limit,
    categoryId = "food",
)
