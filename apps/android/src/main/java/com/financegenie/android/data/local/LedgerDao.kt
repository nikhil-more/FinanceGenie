package com.financegenie.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LedgerDao {
    @Query("SELECT * FROM ledger_settings WHERE id = 1") suspend fun settings(): SettingsEntity
    @Query("SELECT revision FROM ledger_settings WHERE id = 1") fun observeRevision(): Flow<Long>
    @Query("UPDATE ledger_settings SET revision = :revision WHERE id = 1") suspend fun setRevision(revision: Long)
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM categories ORDER BY id") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM transactions ORDER BY id") suspend fun allTransactions(): List<TransactionEntity>
    @Query("SELECT * FROM budgets ORDER BY month, scope_key") suspend fun budgets(): List<BudgetEntity>
    @Query("SELECT * FROM balance_observations ORDER BY as_of_date, id") suspend fun observations(): List<ObservationEntity>
    @Query("SELECT * FROM source_evidence ORDER BY id") suspend fun evidence(): List<SourceEvidenceEntity>
    @Query("SELECT * FROM source_evidence WHERE change_group_id = :id") suspend fun groupEvidence(id: UUID): List<SourceEvidenceEntity>
    @Query("SELECT * FROM change_groups WHERE id = :id") suspend fun group(id: UUID): ChangeGroupEntity?
    @Query("SELECT * FROM change_items WHERE group_id = :id ORDER BY transaction_id") suspend fun items(id: UUID): List<ChangeItemEntity>
    @Query("SELECT COUNT(*) FROM change_groups") suspend fun groupCount(): Int

    @Insert suspend fun insertAccount(value: AccountEntity)
    @Update suspend fun updateAccount(value: AccountEntity)
    @Insert suspend fun insertCategory(value: CategoryEntity)
    @Update suspend fun updateCategory(value: CategoryEntity)
    @Insert suspend fun insertBudget(value: BudgetEntity)
    @Insert suspend fun insertBudgets(values: List<BudgetEntity>)
    @Update suspend fun updateBudget(value: BudgetEntity)
    @Query("DELETE FROM budgets WHERE id = :id") suspend fun deleteBudget(id: UUID)
    @Insert suspend fun insertObservation(value: ObservationEntity)
    @Update suspend fun updateObservation(value: ObservationEntity)
    @Insert suspend fun insertTransaction(value: TransactionEntity)
    @Update suspend fun updateTransaction(value: TransactionEntity)
    @Insert suspend fun insertGroup(value: ChangeGroupEntity)
    @Insert suspend fun insertItems(values: List<ChangeItemEntity>)
    @Insert suspend fun insertEvidence(values: List<SourceEvidenceEntity>)
    @Query("UPDATE change_groups SET is_undone = 1 WHERE id = :id") suspend fun markUndone(id: UUID)
    @Query("UPDATE source_evidence SET is_active = 0 WHERE id IN (:ids)") suspend fun detachEvidence(ids: List<UUID>)

    @Query("""
        SELECT * FROM transactions
        WHERE is_deleted = 0 AND effective_date >= :start AND effective_date < :end
          AND (:accountId IS NULL OR account_id = :accountId OR destination_account_id = :accountId)
          AND (:categoryId IS NULL OR category_id = :categoryId
               OR (category_id IS NULL AND kind = 'EXPENSE' AND :categoryId = 'uncategorized'))
          AND (merchant LIKE :search ESCAPE '\' OR notes LIKE :search ESCAPE '\')
        ORDER BY effective_date DESC, meta_createdAtMillis DESC, id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun transactions(
        start: LocalDate,
        end: LocalDate,
        accountId: UUID?,
        categoryId: String?,
        search: String,
        limit: Int,
        offset: Int,
    ): List<TransactionEntity>
}
