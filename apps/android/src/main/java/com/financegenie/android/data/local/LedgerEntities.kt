package com.financegenie.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomWarnings
import com.financegenie.android.data.RecordMetadata
import com.financegenie.android.data.TransactionSource
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.AdjustmentDirection
import com.financegenie.android.domain.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Entity(tableName = "ledger_settings")
internal data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "local_id") val localId: UUID,
    val currency: String,
    @ColumnInfo(name = "reporting_zone") val reportingZone: String,
    val revision: Long,
)

@Entity(tableName = "accounts", indices = [Index("is_archived")])
internal data class AccountEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val kind: AccountKind,
    @ColumnInfo(name = "opening_date") val openingDate: LocalDate,
    @ColumnInfo(name = "opening_balance_minor") val openingBalanceMinor: Long,
    val currency: String,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @Embedded(prefix = "meta_") val metadata: RecordMetadata,
)

@Entity(tableName = "categories")
internal data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @Embedded(prefix = "meta_") val metadata: RecordMetadata,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT, deferred = true),
        ForeignKey(AccountEntity::class, ["id"], ["destination_account_id"], onDelete = ForeignKey.RESTRICT, deferred = true),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT, deferred = true),
        ForeignKey(TransactionEntity::class, ["id"], ["original_expense_id"], onDelete = ForeignKey.RESTRICT, deferred = true),
    ],
    indices = [
        Index("effective_date"), Index("account_id"), Index("destination_account_id"),
        Index("category_id"), Index("original_expense_id"),
    ],
)
internal data class TransactionEntity(
    @PrimaryKey val id: UUID,
    val kind: TransactionKind,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "account_id") val accountId: UUID,
    @ColumnInfo(name = "effective_date") val effectiveDate: LocalDate,
    @ColumnInfo(name = "destination_account_id") val destinationAccountId: UUID?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "original_expense_id") val originalExpenseId: UUID?,
    @ColumnInfo(name = "unlinked_refund_confirmed") val unlinkedRefundConfirmed: Boolean,
    @ColumnInfo(name = "adjustment_direction") val adjustmentDirection: AdjustmentDirection?,
    @ColumnInfo(name = "adjustment_reason") val adjustmentReason: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant?,
    @ColumnInfo(name = "original_zone_id") val originalZoneId: String?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    val merchant: String,
    val notes: String,
    val source: TransactionSource,
    @Embedded(prefix = "meta_") val metadata: RecordMetadata,
)

@Entity(
    tableName = "budgets",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["month", "scope_key"], unique = true), Index("category_id")],
)
internal data class BudgetEntity(
    @PrimaryKey val id: UUID,
    val month: YearMonth,
    @ColumnInfo(name = "limit_minor") val limitMinor: Long,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "scope_key") val scopeKey: String,
    @Embedded(prefix = "meta_") val metadata: RecordMetadata,
)

@Entity(
    tableName = "balance_observations",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("account_id"), Index("as_of_date")],
)
internal data class ObservationEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "account_id") val accountId: UUID,
    @ColumnInfo(name = "as_of_date") val asOfDate: LocalDate,
    @ColumnInfo(name = "reported_balance_minor") val reportedBalanceMinor: Long,
    @Embedded(prefix = "meta_") val metadata: RecordMetadata,
)

@Entity(tableName = "change_groups")
internal data class ChangeGroupEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "created_at_ms") val createdAtMillis: Long,
    @ColumnInfo(name = "is_undone") val isUndone: Boolean = false,
)

// Prior rows are payloads, not live entities; their keys/indexes intentionally do not transfer.
@SuppressWarnings(RoomWarnings.PRIMARY_KEY_FROM_EMBEDDED_IS_DROPPED, RoomWarnings.INDEX_FROM_EMBEDDED_ENTITY_IS_DROPPED)
@Entity(
    tableName = "change_items",
    primaryKeys = ["group_id", "transaction_id"],
    foreignKeys = [ForeignKey(ChangeGroupEntity::class, ["id"], ["group_id"], onDelete = ForeignKey.CASCADE)],
)
internal data class ChangeItemEntity(
    @ColumnInfo(name = "group_id") val groupId: UUID,
    @ColumnInfo(name = "transaction_id") val transactionId: UUID,
    @ColumnInfo(name = "after_revision") val afterRevision: Long,
    @Embedded(prefix = "before_") val previous: TransactionEntity?,
)

/** Batch bookkeeping only; CSV parsing/commit/undo UI is implemented in Step 7. */
@Entity(
    tableName = "import_batches",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["account_id", "fingerprint"], unique = true)],
)
internal data class ImportBatchEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "account_id") val accountId: UUID,
    val fingerprint: String,
    @ColumnInfo(name = "mapping_version") val mappingVersion: Int,
    @ColumnInfo(name = "accepted_count") val acceptedCount: Int,
    @ColumnInfo(name = "is_undone") val isUndone: Boolean,
    @ColumnInfo(name = "created_at_ms") val createdAtMillis: Long,
)

@Entity(
    tableName = "source_evidence",
    foreignKeys = [
        ForeignKey(TransactionEntity::class, ["id"], ["transaction_id"], onDelete = ForeignKey.RESTRICT, deferred = true),
        ForeignKey(ImportBatchEntity::class, ["id"], ["import_batch_id"], onDelete = ForeignKey.RESTRICT, deferred = true),
        ForeignKey(ChangeGroupEntity::class, ["id"], ["change_group_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["source", "source_key"], unique = true), Index("transaction_id"),
        Index("import_batch_id"), Index("change_group_id"),
    ],
)
internal data class SourceEvidenceEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "transaction_id") val transactionId: UUID,
    val source: TransactionSource,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "import_batch_id") val importBatchId: UUID?,
    @ColumnInfo(name = "change_group_id") val changeGroupId: UUID,
    @ColumnInfo(name = "recorded_at_ms") val recordedAtMillis: Long,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)
