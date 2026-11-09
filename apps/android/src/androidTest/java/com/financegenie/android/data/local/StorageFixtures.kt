package com.financegenie.android.data.local

import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.TransactionKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal val storageClock: Clock = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneOffset.UTC)
internal val storageBankId: UUID = UUID(0, 1)
internal val storageCashId: UUID = UUID(0, 2)
internal val storageCardId: UUID = UUID(0, 3)

internal fun storageAccounts(): List<LedgerAccount> = listOf(
    LedgerAccount(storageBankId, "Synthetic Bank", AccountKind.BANK, LocalDate.of(2026, 11, 1)),
    LedgerAccount(storageCashId, "Synthetic Cash", AccountKind.CASH, LocalDate.of(2026, 11, 1)),
    LedgerAccount(storageCardId, "Synthetic Card", AccountKind.CREDIT_CARD, LocalDate.of(2026, 11, 1)),
)

internal fun storageExpense(
    number: Long = 100,
    amount: Long = 24_950,
    date: LocalDate = LocalDate.of(2026, 11, 2),
    accountId: UUID = storageBankId,
): LedgerTransaction = LedgerTransaction(
    id = UUID(0, number),
    kind = TransactionKind.EXPENSE,
    amountMinor = amount,
    accountId = accountId,
    effectiveDate = date,
    categoryId = "food",
)
