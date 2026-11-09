package com.financegenie.android.data.local

import com.financegenie.android.data.LedgerSettings
import com.financegenie.android.data.RecordMetadata
import com.financegenie.android.data.SourceEvidenceInput
import com.financegenie.android.data.SourceEvidenceRecord
import com.financegenie.android.data.StoredRecord
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.domain.BalanceObservation
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.MonthlyBudget

internal fun SettingsEntity.toSettings(): LedgerSettings = LedgerSettings(localId, currency, reportingZone, revision)

internal fun AccountEntity.toRecord(): StoredRecord<LedgerAccount> = StoredRecord(
    LedgerAccount(id, name, kind, openingDate, openingBalanceMinor, currency, isArchived), metadata,
)

internal fun LedgerAccount.toEntity(metadata: RecordMetadata): AccountEntity = AccountEntity(
    id, name, kind, openingDate, openingBalanceMinor, currency, isArchived, metadata,
)

internal fun CategoryEntity.toRecord(): StoredRecord<LedgerCategory> = StoredRecord(
    LedgerCategory(id, name, isArchived), metadata,
)

internal fun LedgerCategory.toEntity(metadata: RecordMetadata): CategoryEntity = CategoryEntity(id, name, isArchived, metadata)

internal fun BudgetEntity.toRecord(): StoredRecord<MonthlyBudget> {
    require(scopeKey == budgetScopeKey(categoryId)) { "Stored budget scope is inconsistent" }
    return StoredRecord(MonthlyBudget(id, month, limitMinor, categoryId), metadata)
}

internal fun MonthlyBudget.toEntity(metadata: RecordMetadata): BudgetEntity = BudgetEntity(
    id, month, limitMinor, categoryId, budgetScopeKey(categoryId), metadata,
)

internal fun budgetScopeKey(categoryId: String?): String = categoryId?.let { "category:$it" } ?: "overall"

internal fun ObservationEntity.toRecord(): StoredRecord<BalanceObservation> = StoredRecord(
    BalanceObservation(id, accountId, asOfDate, reportedBalanceMinor), metadata,
)

internal fun BalanceObservation.toEntity(metadata: RecordMetadata): ObservationEntity = ObservationEntity(
    id, accountId, asOfDate, reportedBalanceMinor, metadata,
)

internal fun TransactionEntity.toRecord(): TransactionRecord = TransactionRecord(
    transaction = LedgerTransaction(
        id = id,
        kind = kind,
        amountMinor = amountMinor,
        accountId = accountId,
        effectiveDate = effectiveDate,
        destinationAccountId = destinationAccountId,
        categoryId = categoryId,
        originalExpenseId = originalExpenseId,
        unlinkedRefundConfirmed = unlinkedRefundConfirmed,
        adjustmentDirection = adjustmentDirection,
        adjustmentReason = adjustmentReason,
        occurredAt = occurredAt,
        originalZoneId = originalZoneId,
        isDeleted = isDeleted,
    ),
    details = TransactionDetails(merchant, notes, source),
    metadata = metadata,
)

internal fun TransactionRecord.toEntity(): TransactionEntity = TransactionEntity(
    id = transaction.id,
    kind = transaction.kind,
    amountMinor = transaction.amountMinor,
    accountId = transaction.accountId,
    effectiveDate = transaction.effectiveDate,
    destinationAccountId = transaction.destinationAccountId,
    categoryId = transaction.categoryId,
    originalExpenseId = transaction.originalExpenseId,
    unlinkedRefundConfirmed = transaction.unlinkedRefundConfirmed,
    adjustmentDirection = transaction.adjustmentDirection,
    adjustmentReason = transaction.adjustmentReason,
    occurredAt = transaction.occurredAt,
    originalZoneId = transaction.originalZoneId,
    isDeleted = transaction.isDeleted,
    merchant = details.merchant,
    notes = details.notes,
    source = details.source,
    metadata = metadata,
)

internal fun SourceEvidenceEntity.toRecord(): SourceEvidenceRecord = SourceEvidenceRecord(
    SourceEvidenceInput(id, transactionId, source, sourceKey, importBatchId),
    changeGroupId,
    recordedAtMillis,
    isActive,
)
