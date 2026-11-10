package com.financegenie.android.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.financegenie.android.data.DuplicateRecordException
import com.financegenie.android.data.BudgetCopyResult
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.RecordMetadata
import com.financegenie.android.data.SourceEvidenceInput
import com.financegenie.android.data.StaleRecordException
import com.financegenie.android.data.StoredLedger
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.data.TransactionSource
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.data.ImportBatchRecord
import com.financegenie.android.data.CsvReplayException
import com.financegenie.android.data.CsvUndoConflict
import com.financegenie.android.csv.CsvImportRequest
import com.financegenie.android.csv.planCsvImport
import com.financegenie.android.domain.BalanceObservation
import com.financegenie.android.domain.DateRange
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerValidator
import com.financegenie.android.domain.MonthlyBudget
import com.financegenie.android.domain.planBudgetCopy
import java.time.Clock
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomLedgerRepository(
    private val database: FinanceGenieDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : LedgerRepository {
    private val dao = database.ledgerDao()
    private val validator = LedgerValidator(clock)
    @Volatile private var cachedLedger: CachedLedger? = null

    override suspend fun read(): StoredLedger = database.withTransaction { load() }

    override fun observe(): Flow<StoredLedger> = dao.observeRevision()
        .map { read() }
        .distinctUntilChanged()

    override suspend fun saveAccount(account: LedgerAccount, expectedRevision: Long?): RecordMetadata = mutate { before ->
        val old = before.accounts.find { it.value.id == account.id }
        val metadata = nextMetadata(old?.metadata, expectedRevision)
        val after = before.snapshot.copy(accounts = before.snapshot.accounts.filterNot { it.id == account.id } + account)
        validator.validateChange(before.snapshot, after)
        val row = account.toEntity(metadata)
        if (old == null) dao.insertAccount(row) else dao.updateAccount(row)
        metadata
    }

    override suspend fun saveCategory(category: LedgerCategory, expectedRevision: Long?): RecordMetadata = mutate { before ->
        val old = before.categories.find { it.value.id == category.id }
        val metadata = nextMetadata(old?.metadata, expectedRevision)
        val after = before.snapshot.copy(categories = before.snapshot.categories.filterNot { it.id == category.id } + category)
        validator.validateChange(before.snapshot, after)
        val row = category.toEntity(metadata)
        if (old == null) dao.insertCategory(row) else dao.updateCategory(row)
        metadata
    }

    override suspend fun saveBudget(budget: MonthlyBudget, expectedRevision: Long?): RecordMetadata = mutate { before ->
        val old = before.budgets.find { it.value.id == budget.id }
        val metadata = nextMetadata(old?.metadata, expectedRevision)
        require(old == null || (old.value.month == budget.month && old.value.categoryId == budget.categoryId)) {
            "Budget month and category cannot change"
        }
        val after = before.snapshot.copy(budgets = before.snapshot.budgets.filterNot { it.id == budget.id } + budget)
        validator.validateChange(before.snapshot, after)
        val row = budget.toEntity(metadata)
        if (old == null) dao.insertBudget(row) else dao.updateBudget(row)
        metadata
    }

    override suspend fun removeBudget(id: UUID, expectedRevision: Long) = mutate { before ->
        val old = before.budgets.find { it.value.id == id } ?: throw StaleRecordException()
        if (old.metadata.revision != expectedRevision) throw StaleRecordException()
        validator.validateChange(before.snapshot, before.snapshot.copy(budgets = before.snapshot.budgets.filterNot { it.id == id }))
        dao.deleteBudget(id)
    }

    override suspend fun copyBudgetsToNextMonth(fromMonth: YearMonth, expectedLedgerRevision: Long): BudgetCopyResult = database.withTransaction {
        val before = load()
        if (before.settings.revision != expectedLedgerRevision) throw StaleRecordException()
        val plan = planBudgetCopy(before.snapshot, fromMonth)
        val copies = plan.toCopy.map { it.copy(id = UUID.randomUUID(), month = plan.toMonth) }
        if (copies.isNotEmpty()) {
            validator.validateChange(before.snapshot, before.snapshot.copy(budgets = before.snapshot.budgets + copies))
            dao.insertBudgets(copies.map { it.toEntity(nextMetadata(null, null)) })
            dao.setRevision(Math.addExact(before.settings.revision, 1L))
        }
        BudgetCopyResult(copies.size, plan.existingCount, plan.archivedCount)
    }

    override suspend fun saveObservation(observation: BalanceObservation, expectedRevision: Long?): RecordMetadata = mutate { before ->
        val old = before.observations.find { it.value.id == observation.id }
        val metadata = nextMetadata(old?.metadata, expectedRevision)
        val after = before.snapshot.copy(observations = before.snapshot.observations.filterNot { it.id == observation.id } + observation)
        validator.validateChange(before.snapshot, after)
        val row = observation.toEntity(metadata)
        if (old == null) dao.insertObservation(row) else dao.updateObservation(row)
        metadata
    }

    override suspend fun applyTransactions(
        changes: List<TransactionWrite>,
        evidence: List<SourceEvidenceInput>,
    ): UUID = mutate { before ->
        require(changes.isNotEmpty()) { "A change group must contain a transaction" }
        writeGroup(before, changes, evidence)
    }

    private suspend fun writeGroup(before: StoredLedger, changes: List<TransactionWrite>, evidence: List<SourceEvidenceInput>, referenceReceipts: Boolean = false): UUID {
        require(changes.isNotEmpty() || evidence.isNotEmpty())
        require(changes.map { it.transaction.id }.distinct().size == changes.size) { "Change group IDs must be unique" }
        val oldById = before.transactions.associateBy { it.transaction.id }
        val prepared = changes.map { change ->
            val old = oldById[change.transaction.id]
            if (old == null) {
                require(!change.transaction.isDeleted) { "A new event cannot start deleted" }
            } else {
                require(old.details.source == change.details.source) { "Editing cannot replace original provenance" }
            }
            PreparedWrite(
                previous = old,
                current = TransactionRecord(change.transaction, change.details, nextMetadata(old?.metadata, change.expectedRevision)),
            )
        }
        val afterById = oldById.toMutableMap()
        prepared.forEach { afterById[it.current.transaction.id] = it.current }
        validator.validateChange(before.snapshot, before.snapshot.copy(transactions = afterById.values.map { it.transaction }))

        val groupId = UUID.randomUUID()
        dao.insertGroup(ChangeGroupEntity(groupId, clock.millis()))
        prepared.forEach { write ->
            if (write.previous == null) dao.insertTransaction(write.current.toEntity())
            else dao.updateTransaction(write.current.toEntity())
        }
        val items = prepared.map { write ->
            ChangeItemEntity(groupId, write.current.transaction.id, write.current.metadata.revision, write.previous?.toEntity())
        } + if (!referenceReceipts) emptyList() else evidence.map { it.transactionId }.distinct()
            .filterNot { id -> prepared.any { it.current.transaction.id == id } }.map { id ->
                val record = requireNotNull(oldById[id])
                ChangeItemEntity(groupId, id, record.metadata.revision, record.toEntity())
            }
        dao.insertItems(items)
        val automatic = prepared.filter { it.previous == null && it.current.details.source == TransactionSource.MANUAL }
            .map { write ->
                SourceEvidenceInput(UUID.randomUUID(), write.current.transaction.id, TransactionSource.MANUAL, "manual:${write.current.transaction.id}")
            }
        val inputs = evidence + automatic
        require(inputs.map { it.id }.distinct().size == inputs.size) { "Evidence IDs must be unique" }
        require(inputs.map { it.source to it.sourceKey }.distinct().size == inputs.size) { "Evidence source keys must be unique" }
        inputs.forEach { require(afterById.containsKey(it.transactionId)) { "Evidence transaction does not exist" } }
        prepared.filter { it.previous == null && it.current.details.source == TransactionSource.CSV }.forEach { write ->
            require(inputs.any { it.transactionId == write.current.transaction.id && it.source == TransactionSource.CSV }) {
                "An imported event requires normalized source evidence"
            }
        }
        dao.insertEvidence(inputs.map { input ->
            SourceEvidenceEntity(input.id, input.transactionId, input.source, input.sourceKey, input.importBatchId, groupId, clock.millis())
        })
        return groupId
    }

    override suspend fun undoTransactionChange(groupId: UUID) {
        mutate { before ->
            require(dao.importedGroupEvidenceCount(groupId) == 0) { "Use import history to undo an imported batch" }
            val group = requireNotNull(dao.group(groupId)) { "Change group does not exist" }
            check(!group.isUndone) { "Change group has already been undone" }
            val items = dao.items(groupId)
            check(items.isNotEmpty()) { "Change history is incomplete" }
            val current = before.transactions.associateBy { it.transaction.id }
            val restored = items.map { item ->
                val record = current[item.transactionId] ?: throw StaleRecordException()
                val metadata = nextMetadata(record.metadata, item.afterRevision)
                val row = item.previous?.copy(metadata = metadata)
                    ?: record.copy(transaction = record.transaction.copy(isDeleted = true), metadata = metadata).toEntity()
                row.toRecord()
            }
            val after = current.toMutableMap()
            restored.forEach { after[it.transaction.id] = it }
            validator.validateChange(before.snapshot, before.snapshot.copy(transactions = after.values.map { it.transaction }))
            restored.forEach { dao.updateTransaction(it.toEntity()) }
            val createdIds = items.filter { it.previous == null }.map { it.transactionId }.toSet()
            val detachedIds = dao.groupEvidence(groupId).filterNot { it.transactionId in createdIds }.map { it.id }
            if (detachedIds.isNotEmpty()) dao.detachEvidence(detachedIds)
            dao.markUndone(groupId)
        }
    }

    override suspend fun commitCsv(request: CsvImportRequest): UUID = mutate { before ->
        if (before.settings.revision != request.expectedLedgerRevision) throw StaleRecordException()
        val plan = planCsvImport(before, request, clock)
        plan.replay?.let { throw CsvReplayException(it) }
        require(plan.canCommit) { "Review accepted CSV rows before committing" }
        val batchId = UUID.randomUUID()
        dao.insertBatch(ImportBatchEntity(batchId, request.accountId, plan.fingerprint, 1, plan.acceptedCount, false, clock.millis()))
        writeGroup(before, plan.writes, plan.evidence.map { it.copy(importBatchId = batchId) }, referenceReceipts = true)
        batchId
    }

    override suspend fun undoCsv(batchId: UUID, expectedLedgerRevision: Long) = changeCsvStatus(batchId, expectedLedgerRevision, restoring = false)
    override suspend fun restoreCsv(batchId: UUID, expectedLedgerRevision: Long) = changeCsvStatus(batchId, expectedLedgerRevision, restoring = true)

    private suspend fun changeCsvStatus(batchId: UUID, expectedLedgerRevision: Long, restoring: Boolean) = mutate { before ->
        if (before.settings.revision != expectedLedgerRevision) throw StaleRecordException()
        val batch = requireNotNull(dao.batch(batchId))
        require(batch.isUndone == restoring) { "Batch status changed" }
        val evidence = dao.batchEvidence(batchId)
        val groupId = evidence.map { it.changeGroupId }.distinct().single()
        val group = requireNotNull(dao.group(groupId))
        require(group.isUndone == restoring)
        val items = dao.items(groupId)
        val records = before.transactions.associateBy { it.transaction.id }
        val createdIds = items.filter { it.previous == null }.map { it.transactionId }.toSet()
        if (!restoring && before.evidence.any { it.isActive && it.input.transactionId in createdIds && it.changeGroupId != groupId }) throw CsvUndoConflict()
        val updated = items.mapNotNull { item ->
            val record = records[item.transactionId] ?: throw CsvUndoConflict()
            val expected = if (restoring && item.previous == null) Math.addExact(item.afterRevision, 1L) else item.afterRevision
            if (record.metadata.revision != expected || record.transaction.isDeleted != (restoring && item.previous == null)) throw CsvUndoConflict()
            if (item.previous != null) null else record.copy(transaction = record.transaction.copy(isDeleted = !restoring),
                metadata = nextMetadata(record.metadata, expected))
        }
        val after = records.toMutableMap()
        updated.forEach { after[it.transaction.id] = it }
        try { validator.validateChange(before.snapshot, before.snapshot.copy(transactions = after.values.map { it.transaction })) }
        catch (_: IllegalArgumentException) { throw CsvUndoConflict() }
        updated.forEach { record ->
            dao.updateTransaction(record.toEntity())
            if (restoring) dao.setItemRevision(groupId, record.transaction.id, record.metadata.revision)
        }
        dao.setBatchEvidenceActive(batchId, restoring)
        dao.setBatchUndone(batchId, !restoring)
        dao.setGroupUndone(groupId, !restoring)
    }

    override suspend fun transactions(
        range: DateRange,
        accountId: UUID?,
        categoryId: String?,
        search: String,
        limit: Int,
        offset: Int,
    ): List<TransactionRecord> {
        require(limit in 1..200 && offset >= 0) { "Invalid transaction page" }
        require(search.length <= 200) { "Search must contain at most 200 characters" }
        val escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.transactions(range.startInclusive, range.endExclusive, accountId, categoryId, "%$escaped%", limit, offset)
            .map { it.toRecord() }
    }

    private suspend fun load(): StoredLedger {
        // Called inside a Room transaction. Every committed repository write advances
        // this revision, including writes through another repository instance.
        val settings = dao.settings().toSettings()
        // A caller can also nest repository operations in an outer transaction.
        // Its rollback can reuse a revision later: total_changes tracks even rolled-
        // back writes on this connection; data_version tracks other connections.
        val localChanges = dao.connectionChanges()
        val dataVersion = dao.dataVersion(SimpleSQLiteQuery("PRAGMA data_version"))
        cachedLedger?.takeIf { it.ledger.settings == settings && it.localChanges == localChanges && it.dataVersion == dataVersion }
            ?.let { return it.ledger }
        return StoredLedger(
            settings = settings,
            accounts = dao.accounts().map { it.toRecord() },
            categories = dao.categories().map { it.toRecord() },
            transactions = dao.allTransactions().map { it.toRecord() },
            budgets = dao.budgets().map { it.toRecord() },
            observations = dao.observations().map { it.toRecord() },
            evidence = dao.evidence().map { it.toRecord() },
            importBatches = dao.batches().map { ImportBatchRecord(it.id, it.accountId, it.fingerprint, it.acceptedCount, it.isUndone, it.createdAtMillis) },
        ).also { cachedLedger = CachedLedger(it, localChanges, dataVersion) }
    }

    private suspend fun <T> mutate(action: suspend (StoredLedger) -> T): T = database.withTransaction {
        val before = load()
        val result = action(before)
        dao.setRevision(Math.addExact(before.settings.revision, 1L))
        result
    }

    private fun nextMetadata(previous: RecordMetadata?, expectedRevision: Long?): RecordMetadata {
        if (previous == null) {
            if (expectedRevision != null) throw StaleRecordException()
            return RecordMetadata(1, clock.millis(), clock.millis())
        }
        if (expectedRevision == null) throw DuplicateRecordException()
        if (expectedRevision != previous.revision) throw StaleRecordException()
        return previous.copy(revision = Math.addExact(previous.revision, 1L), updatedAtMillis = clock.millis())
    }

    private data class PreparedWrite(val previous: TransactionRecord?, val current: TransactionRecord)
    private data class CachedLedger(val ledger: StoredLedger, val localChanges: Long, val dataVersion: Long)
}
