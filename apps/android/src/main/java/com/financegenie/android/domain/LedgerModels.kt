package com.financegenie.android.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

const val LEDGER_CURRENCY = "INR"
const val UNCATEGORIZED_ID = "uncategorized"

enum class AccountKind { CASH, BANK, CREDIT_CARD }

enum class TransactionKind { EXPENSE, INCOME, TRANSFER, REFUND, ADJUSTMENT }

enum class AdjustmentDirection { INCREASE, DECREASE }

data class LedgerAccount(
    val id: UUID,
    val name: String,
    val kind: AccountKind,
    val openingDate: LocalDate,
    val openingBalanceMinor: Long = 0,
    val currency: String = LEDGER_CURRENCY,
    val isArchived: Boolean = false,
) {
    init {
        require(name.isNotBlank() && name.length <= 80) { "Account name must contain 1–80 characters" }
        require(currency == LEDGER_CURRENCY) { "Only INR is supported" }
        Money.requireInput(openingBalanceMinor, allowZero = true, signed = true)
    }
}

data class LedgerCategory(
    val id: String,
    val name: String,
    val isArchived: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 100) { "Category ID must contain 1–100 characters" }
        require(name.isNotBlank() && name.length <= 80) { "Category name must contain 1–80 characters" }
        require(id != UNCATEGORIZED_ID || !isArchived) { "Uncategorized cannot be archived" }
    }

    companion object {
        fun uncategorized(): LedgerCategory = LedgerCategory(UNCATEGORIZED_ID, "Uncategorized")
    }
}

/** A committed financial event. Drafts and detection candidates are separate types. */
data class LedgerTransaction(
    val id: UUID,
    val kind: TransactionKind,
    val amountMinor: Long,
    val accountId: UUID,
    val effectiveDate: LocalDate,
    val destinationAccountId: UUID? = null,
    val categoryId: String? = null,
    val originalExpenseId: UUID? = null,
    val unlinkedRefundConfirmed: Boolean = false,
    val adjustmentDirection: AdjustmentDirection? = null,
    val adjustmentReason: String? = null,
    val occurredAt: Instant? = null,
    val originalZoneId: String? = null,
    val isDeleted: Boolean = false,
) {
    init {
        Money.requireInput(amountMinor)
        require(categoryId == null || categoryId.isNotBlank()) { "Category ID cannot be blank" }
        require((occurredAt == null) == (originalZoneId == null)) {
            "Timed events require an instant and its original zone"
        }
        if (originalZoneId != null) ZoneId.of(originalZoneId)
        require((kind == TransactionKind.TRANSFER) == (destinationAccountId != null)) {
            "Only a transfer has a destination account"
        }
        require(destinationAccountId == null || destinationAccountId != accountId) {
            "Transfer accounts must be distinct"
        }
        require(kind == TransactionKind.REFUND || originalExpenseId == null) {
            "Only a refund can reference an expense"
        }
        if (kind == TransactionKind.REFUND) {
            require(categoryId != null) { "A refund requires an explicit category snapshot" }
            require(originalExpenseId != null || unlinkedRefundConfirmed) {
                "An unlinked refund requires explicit confirmation"
            }
        }
        require(!unlinkedRefundConfirmed || (kind == TransactionKind.REFUND && originalExpenseId == null)) {
            "Confirmation is reserved for an unlinked refund"
        }
        require((kind == TransactionKind.ADJUSTMENT) == (adjustmentDirection != null)) {
            "Only an adjustment has an adjustment direction"
        }
        if (kind == TransactionKind.ADJUSTMENT) {
            require(!adjustmentReason.isNullOrBlank() && adjustmentReason.length <= 500) {
                "An adjustment requires a reason of 1–500 characters"
            }
        } else {
            require(adjustmentReason == null) { "Only an adjustment has an adjustment reason" }
        }
    }

    val spendingCategoryId: String?
        get() = when (kind) {
            TransactionKind.EXPENSE, TransactionKind.REFUND -> categoryId ?: UNCATEGORIZED_ID
            else -> null
        }
}

data class MonthlyBudget(
    val id: UUID,
    val month: YearMonth,
    val limitMinor: Long,
    val categoryId: String? = null,
) {
    init {
        Money.requireInput(limitMinor, allowZero = true)
        require(categoryId == null || categoryId.isNotBlank()) { "Category ID cannot be blank" }
    }
}

data class BalanceObservation(
    val id: UUID,
    val accountId: UUID,
    val asOfDate: LocalDate,
    val reportedBalanceMinor: Long,
) {
    init {
        Money.requireInput(reportedBalanceMinor, allowZero = true, signed = true)
    }
}

data class LedgerSnapshot(
    val accounts: List<LedgerAccount> = emptyList(),
    val categories: List<LedgerCategory> = listOf(LedgerCategory.uncategorized()),
    val transactions: List<LedgerTransaction> = emptyList(),
    val budgets: List<MonthlyBudget> = emptyList(),
    val observations: List<BalanceObservation> = emptyList(),
)
