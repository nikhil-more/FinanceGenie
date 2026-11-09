package com.financegenie.android.data

import com.financegenie.android.domain.BalanceObservation
import com.financegenie.android.domain.DateRange
import com.financegenie.android.domain.LEDGER_CURRENCY
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.LedgerSnapshot
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.MonthlyBudget
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class RecordMetadata(val revision: Long, val createdAtMillis: Long, val updatedAtMillis: Long) {
    init {
        require(revision > 0) { "Record revision must be positive" }
    }
}

data class StoredRecord<T>(val value: T, val metadata: RecordMetadata)

enum class TransactionSource { MANUAL, CSV }

data class TransactionDetails(
    val merchant: String = "",
    val notes: String = "",
    val source: TransactionSource = TransactionSource.MANUAL,
) {
    init {
        require(merchant.length <= 200) { "Merchant must contain at most 200 characters" }
        require(notes.length <= 2_000) { "Notes must contain at most 2000 characters" }
    }
}

data class TransactionRecord(
    val transaction: LedgerTransaction,
    val details: TransactionDetails,
    val metadata: RecordMetadata,
)

data class LedgerSettings(
    val localId: UUID,
    val currency: String,
    val reportingZone: String,
    val revision: Long,
) {
    init {
        require(currency == LEDGER_CURRENCY && reportingZone == LedgerDates.reportingZone.id) {
            "Unsupported ledger currency or reporting zone"
        }
        require(revision >= 0) { "Ledger revision cannot be negative" }
    }
}

/** Opaque source identity only; never a raw message, statement row or OTP. */
data class SourceEvidenceInput(
    val id: UUID,
    val transactionId: UUID,
    val source: TransactionSource,
    val sourceKey: String,
    val importBatchId: UUID? = null,
) {
    init {
        require(sourceKey.isNotBlank() && sourceKey.length <= 512) { "Source key must contain 1–512 characters" }
    }
}

data class SourceEvidenceRecord(
    val input: SourceEvidenceInput,
    val changeGroupId: UUID,
    val recordedAtMillis: Long,
    val isActive: Boolean,
)

data class StoredLedger(
    val settings: LedgerSettings,
    val accounts: List<StoredRecord<LedgerAccount>>,
    val categories: List<StoredRecord<LedgerCategory>>,
    val transactions: List<TransactionRecord>,
    val budgets: List<StoredRecord<MonthlyBudget>>,
    val observations: List<StoredRecord<BalanceObservation>>,
    val evidence: List<SourceEvidenceRecord>,
) {
    val snapshot: LedgerSnapshot
        get() = LedgerSnapshot(
            accounts = accounts.map { it.value },
            categories = categories.map { it.value },
            transactions = transactions.map { it.transaction },
            budgets = budgets.map { it.value },
            observations = observations.map { it.value },
        )
}

data class TransactionWrite(
    val transaction: LedgerTransaction,
    val details: TransactionDetails = TransactionDetails(),
    val expectedRevision: Long? = null,
)

class DuplicateRecordException : IllegalArgumentException("A record with this ID already exists")

class StaleRecordException : IllegalStateException("This record changed; reload it before saving or undoing")

data class BudgetCopyResult(val createdCount: Int, val existingCount: Int, val archivedCount: Int)

interface LedgerRepository {
    suspend fun read(): StoredLedger
    fun observe(): Flow<StoredLedger>
    suspend fun saveAccount(account: LedgerAccount, expectedRevision: Long? = null): RecordMetadata
    suspend fun saveCategory(category: LedgerCategory, expectedRevision: Long? = null): RecordMetadata
    suspend fun saveBudget(budget: MonthlyBudget, expectedRevision: Long? = null): RecordMetadata
    suspend fun removeBudget(id: UUID, expectedRevision: Long)
    suspend fun copyBudgetsToNextMonth(fromMonth: YearMonth, expectedLedgerRevision: Long): BudgetCopyResult
    suspend fun saveObservation(observation: BalanceObservation, expectedRevision: Long? = null): RecordMetadata

    /** All events, evidence and prior versions commit together or not at all. */
    suspend fun applyTransactions(
        changes: List<TransactionWrite>,
        evidence: List<SourceEvidenceInput> = emptyList(),
    ): UUID

    suspend fun undoTransactionChange(groupId: UUID)

    suspend fun transactions(
        range: DateRange,
        accountId: UUID? = null,
        categoryId: String? = null,
        search: String = "",
        limit: Int = 50,
        offset: Int = 0,
    ): List<TransactionRecord>
}
