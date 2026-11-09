package com.financegenie.android.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financegenie.android.R
import com.financegenie.android.data.DuplicateRecordException
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.StaleRecordException
import com.financegenie.android.data.StoredLedger
import com.financegenie.android.data.StoredRecord
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.BalanceReconciliation
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.LedgerReport
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.domain.UNCATEGORIZED_ID
import java.time.Clock
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LedgerFilters(
    val month: YearMonth,
    val search: String = "",
    val accountId: UUID? = null,
    val categoryId: String? = null,
    val allDates: Boolean = false,
    val showDeleted: Boolean = false,
)

data class LedgerUiState(
    val filters: LedgerFilters,
    val loading: Boolean = true,
    val ledger: StoredLedger? = null,
    val report: LedgerReport? = null,
    val currentReport: LedgerReport? = null,
    val observations: Map<UUID, BalanceReconciliation> = emptyMap(),
    val rows: List<TransactionRecord> = emptyList(),
    @StringRes val error: Int? = null,
)

data class LedgerNotice(val id: UUID = UUID.randomUUID(), @StringRes val message: Int, val undoGroup: UUID? = null)
private data class LoadResult(val ledger: StoredLedger? = null, val failed: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(private val repository: LedgerRepository, private val clock: Clock) : ViewModel() {
    private val calculator = LedgerCalculator(clock)
    private val filters = MutableStateFlow(LedgerFilters(YearMonth.from(LedgerDates.today(clock))))
    private val reload = MutableStateFlow(0)
    private val loaded = reload.flatMapLatest {
        repository.observe().map { LoadResult(it) }
            .onStart { emit(LoadResult()) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(LoadResult(failed = true))
            }
    }
    val state = combine(loaded, filters) { result, selection ->
        val ledger = result.ledger
        when {
            result.failed -> LedgerUiState(selection, loading = false, error = R.string.error_load)
            ledger == null -> LedgerUiState(selection)
            else -> try {
                val report = calculator.monthlyReport(ledger.snapshot, selection.month)
                val currentMonth = YearMonth.from(LedgerDates.today(clock))
                val current = if (selection.month == currentMonth) report else calculator.monthlyReport(ledger.snapshot, currentMonth)
                val observations = ledger.observations.groupBy { it.value.accountId }.mapValues { (_, entries) ->
                    val latest = entries.maxWith(compareBy({ it.value.asOfDate }, { it.metadata.createdAtMillis }, { it.value.id }))
                    calculator.reconcile(ledger.snapshot, latest.value.id)
                }
                val search = selection.search.trim()
                val rows = ledger.transactions.filter { record ->
                    val event = record.transaction
                    (selection.showDeleted || !event.isDeleted) &&
                        (selection.allDates || YearMonth.from(event.effectiveDate) == selection.month) &&
                        (selection.accountId == null || event.accountId == selection.accountId || event.destinationAccountId == selection.accountId) &&
                        (selection.categoryId == null || event.categoryId == selection.categoryId) &&
                        (search.isEmpty() || record.details.merchant.contains(search, true) || record.details.notes.contains(search, true))
                }.sortedWith(compareByDescending<TransactionRecord> { it.transaction.effectiveDate }
                    .thenByDescending { it.metadata.createdAtMillis }.thenBy { it.transaction.id })
                LedgerUiState(selection, false, ledger, report, current, observations, rows)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LedgerUiState(selection, loading = false, error = R.string.error_load)
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState(filters.value))

    private val mutableEditor = MutableStateFlow<LedgerEditor?>(null)
    val editor = mutableEditor.asStateFlow()
    private val mutableFormError = MutableStateFlow<Int?>(null)
    val formError = mutableFormError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<LedgerNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableDelete = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val deleting = mutableDelete.asStateFlow()

    fun retry() { reload.value += 1 }
    fun filter(change: (LedgerFilters) -> LedgerFilters) { filters.value = change(filters.value) }
    fun moveMonth(delta: Long) {
        val month = filters.value.month.plusMonths(delta)
        if (month <= YearMonth.from(LedgerDates.today(clock))) filter { it.copy(month = month) }
    }

    fun newAccount() = open(AccountDraft(UUID.randomUUID(), date = LedgerDates.today(clock).toString()))
    fun editAccount(record: StoredRecord<LedgerAccount>) = record.value.let {
        open(AccountDraft(it.id, record.metadata.revision, it.name, it.kind, inputMagnitude(it.openingBalanceMinor),
            it.openingBalanceMinor < 0, it.openingDate.toString(), it.isArchived))
    }
    fun newCategory() {
        val id = UUID.randomUUID()
        open(CategoryDraft(id, id.toString()))
    }
    fun editCategory(record: StoredRecord<LedgerCategory>) = open(
        CategoryDraft(UUID.randomUUID(), record.value.id, record.metadata.revision, record.value.name, record.value.isArchived),
    )
    fun observeBalance(account: LedgerAccount) = open(
        ObservationDraft(UUID.randomUUID(), account.id, account.kind, date = LedgerDates.today(clock).toString()),
    )
    fun newTransaction() = open(TransactionDraft(UUID.randomUUID(), date = LedgerDates.today(clock).toString(), categoryId = UNCATEGORIZED_ID))
    fun editTransaction(record: TransactionRecord) = record.transaction.let {
        open(TransactionDraft(it.id, record, it.kind, inputMagnitude(it.amountMinor), it.effectiveDate.toString(), it.accountId,
            it.destinationAccountId, it.categoryId, it.originalExpenseId, it.unlinkedRefundConfirmed,
            it.adjustmentDirection ?: com.financegenie.android.domain.AdjustmentDirection.INCREASE,
            it.adjustmentReason.orEmpty(), record.details.merchant, record.details.notes))
    }

    private fun open(draft: LedgerEditor) {
        if (busy.value) return
        mutableFormError.value = null
        mutableEditor.value = draft
    }
    fun updateEditor(draft: LedgerEditor) {
        if (!busy.value && draft.id == editor.value?.id) {
            mutableEditor.value = draft
            mutableFormError.value = null
        }
    }
    fun closeEditor() {
        if (!busy.value) {
            mutableEditor.value = null
            mutableFormError.value = null
        }
    }
    fun saveEditor() {
        val draft = editor.value ?: return
        work(form = true) {
            val notice = when (draft) {
                is AccountDraft -> {
                    repository.saveAccount(draft.account(clock), draft.expectedRevision)
                    LedgerNotice(message = R.string.account_saved)
                }
                is CategoryDraft -> {
                    repository.saveCategory(draft.category(), draft.expectedRevision)
                    LedgerNotice(message = R.string.category_saved)
                }
                is ObservationDraft -> {
                    repository.saveObservation(draft.observation(clock))
                    LedgerNotice(message = R.string.balance_saved)
                }
                is TransactionDraft -> LedgerNotice(message = R.string.transaction_saved,
                    undoGroup = repository.applyTransactions(listOf(draft.write(clock))))
            }
            mutableEditor.value = null
            mutableNotice.value = notice
        }
    }

    fun archive(record: StoredRecord<LedgerAccount>) = work {
        repository.saveAccount(record.value.copy(isArchived = !record.value.isArchived), record.metadata.revision)
        mutableNotice.value = LedgerNotice(message = R.string.archive_updated)
    }
    fun archiveCategory(record: StoredRecord<LedgerCategory>) = work {
        repository.saveCategory(record.value.copy(isArchived = !record.value.isArchived), record.metadata.revision)
        mutableNotice.value = LedgerNotice(message = R.string.archive_updated)
    }
    fun askDelete(record: TransactionRecord) {
        if (busy.value) return
        val refunds = state.value.ledger?.transactions.orEmpty().filter {
            !it.transaction.isDeleted && it.transaction.originalExpenseId == record.transaction.id
        }
        mutableDelete.value = listOf(record) + refunds
    }
    fun cancelDelete() { if (!busy.value) mutableDelete.value = emptyList() }
    fun confirmDelete() {
        val records = deleting.value.takeIf { it.isNotEmpty() } ?: return
        work {
            val group = repository.applyTransactions(records.map {
                TransactionWrite(it.transaction.copy(isDeleted = true), it.details, it.metadata.revision)
            })
            mutableDelete.value = emptyList()
            mutableNotice.value = LedgerNotice(message = R.string.transaction_deleted, undoGroup = group)
        }
    }
    fun restore(record: TransactionRecord) = work {
        val group = repository.applyTransactions(listOf(TransactionWrite(record.transaction.copy(isDeleted = false), record.details, record.metadata.revision)))
        mutableNotice.value = LedgerNotice(message = R.string.transaction_restored, undoGroup = group)
    }
    fun undo(notice: LedgerNotice) {
        val group = notice.undoGroup ?: return
        work {
            repository.undoTransactionChange(group)
            mutableNotice.value = LedgerNotice(message = R.string.change_undone)
        }
    }
    fun consumeNotice(id: UUID) { if (notice.value?.id == id) mutableNotice.value = null }

    private fun work(form: Boolean = false, action: suspend () -> Unit) {
        // Set synchronously before launching: two taps in the same frame cannot enqueue two writes.
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableFormError.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = errorMessage(error, editingOpening = form && editor.value is AccountDraft)
                if (form) mutableFormError.value = message else mutableNotice.value = LedgerNotice(message = message)
            } finally {
                mutableBusy.value = false
            }
        }
    }
}

@StringRes
private fun errorMessage(error: Exception, editingOpening: Boolean): Int = when (error) {
    is FormValidation -> error.messageId
    is StaleRecordException -> R.string.error_stale
    is DuplicateRecordException -> R.string.error_duplicate
    else -> when (error.message) {
        "Linked refunds exceed the expense" -> R.string.error_refund_limit
        "A linked refund requires a non-deleted expense", "Original expense does not exist" -> R.string.error_refund_link
        "Cannot select an archived account", "Cannot select an archived destination" -> R.string.error_archived_account
        "Cannot select an archived category" -> R.string.error_archived_category
        "Transaction predates account opening", "Transfer predates destination account opening" ->
            if (editingOpening) R.string.error_opening_history else R.string.error_opening_date
        "Observation must be within the recorded account lifetime" -> R.string.error_observation_date
        else -> R.string.error_save
    }
}
