package com.financegenie.android.ui

import androidx.annotation.StringRes
import com.financegenie.android.R
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.AdjustmentDirection
import com.financegenie.android.domain.BalanceObservation
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.Money
import com.financegenie.android.domain.TransactionKind
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

sealed interface LedgerEditor { val id: UUID }

data class AccountDraft(
    override val id: UUID,
    val expectedRevision: Long? = null,
    val name: String = "",
    val kind: AccountKind = AccountKind.BANK,
    val balance: String = "",
    val negative: Boolean = false,
    val date: String,
    val isArchived: Boolean = false,
) : LedgerEditor {
    fun account(clock: Clock): LedgerAccount {
        if (name.isBlank() || name.trim().length > 80) throw FormValidation(R.string.error_required_name)
        return LedgerAccount(id, name.trim(), kind, formDate(date, clock), signedAmount(balance, negative), isArchived = isArchived)
    }
}

data class CategoryDraft(
    override val id: UUID,
    val categoryId: String,
    val expectedRevision: Long? = null,
    val name: String = "",
    val isArchived: Boolean = false,
) : LedgerEditor {
    fun category(): LedgerCategory {
        if (name.isBlank() || name.trim().length > 80) throw FormValidation(R.string.error_required_name)
        return LedgerCategory(categoryId, name.trim(), isArchived)
    }
}

data class ObservationDraft(
    override val id: UUID,
    val accountId: UUID,
    val accountKind: AccountKind,
    val balance: String = "",
    val negative: Boolean = accountKind == AccountKind.CREDIT_CARD,
    val date: String,
) : LedgerEditor {
    fun observation(clock: Clock): BalanceObservation = BalanceObservation(
        id, accountId, formDate(date, clock), signedAmount(balance, negative),
    )
}

data class TransactionDraft(
    override val id: UUID,
    val original: TransactionRecord? = null,
    val kind: TransactionKind = TransactionKind.EXPENSE,
    val amount: String = "",
    val date: String,
    val accountId: UUID? = null,
    val destinationId: UUID? = null,
    val categoryId: String? = null,
    val originalExpenseId: UUID? = null,
    val confirmedUnlinked: Boolean = false,
    val direction: AdjustmentDirection = AdjustmentDirection.INCREASE,
    val reason: String = "",
    val merchant: String = "",
    val notes: String = "",
) : LedgerEditor {
    fun write(clock: Clock): TransactionWrite {
        val account = accountId ?: throw FormValidation(R.string.error_account)
        if (kind == TransactionKind.TRANSFER && (destinationId == null || destinationId == account)) {
            throw FormValidation(R.string.error_destination)
        }
        if (kind in setOf(TransactionKind.EXPENSE, TransactionKind.REFUND) && categoryId == null) {
            throw FormValidation(R.string.error_category)
        }
        if (kind == TransactionKind.REFUND && originalExpenseId == null && !confirmedUnlinked) {
            throw FormValidation(R.string.error_unlinked_refund)
        }
        if (kind == TransactionKind.ADJUSTMENT && (reason.isBlank() || reason.length > 500)) {
            throw FormValidation(R.string.error_reason)
        }
        if (merchant.length > 200 || notes.length > 2_000) throw FormValidation(R.string.error_details)
        val effectiveDate = formDate(date, clock)
        val previous = original?.transaction
        val keepTime = previous != null && previous.effectiveDate == effectiveDate
        val event = LedgerTransaction(
            id = id,
            kind = kind,
            amountMinor = positiveAmount(amount),
            accountId = account,
            effectiveDate = effectiveDate,
            destinationAccountId = destinationId.takeIf { kind == TransactionKind.TRANSFER },
            categoryId = categoryId.takeIf { kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND },
            originalExpenseId = originalExpenseId.takeIf { kind == TransactionKind.REFUND },
            unlinkedRefundConfirmed = kind == TransactionKind.REFUND && originalExpenseId == null && confirmedUnlinked,
            adjustmentDirection = direction.takeIf { kind == TransactionKind.ADJUSTMENT },
            adjustmentReason = reason.trim().takeIf { kind == TransactionKind.ADJUSTMENT },
            occurredAt = previous?.occurredAt.takeIf { keepTime },
            originalZoneId = previous?.originalZoneId.takeIf { keepTime },
        )
        return TransactionWrite(
            event,
            TransactionDetails(merchant.trim(), notes, original?.details?.source ?: com.financegenie.android.data.TransactionSource.MANUAL),
            original?.metadata?.revision,
        )
    }
}

class FormValidation(@StringRes val messageId: Int) : IllegalArgumentException()

internal fun formDate(text: String, clock: Clock): LocalDate {
    val date = try { LocalDate.parse(text.trim()) } catch (_: DateTimeParseException) {
        throw FormValidation(R.string.error_date)
    }
    if (date > LedgerDates.today(clock)) throw FormValidation(R.string.error_future_date)
    return date
}

internal fun positiveAmount(text: String): Long = try { Money.parseAmount(text) } catch (_: IllegalArgumentException) {
    throw FormValidation(R.string.error_amount)
}

private fun signedAmount(text: String, negative: Boolean): Long {
    val minor = try { Money.parseAmount(text.ifBlank { "0" }, allowZero = true) } catch (_: IllegalArgumentException) {
        throw FormValidation(R.string.error_amount)
    }
    return if (negative) -minor else minor
}

internal fun inputMagnitude(minor: Long): String = BigDecimal.valueOf(minor, 2).abs().toPlainString()
