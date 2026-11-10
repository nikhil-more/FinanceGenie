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
import com.financegenie.android.domain.AnalyticsWindow
import com.financegenie.android.domain.DateRange
import com.financegenie.android.domain.LedgerAnalytics
import com.financegenie.android.domain.ReportRequest
import com.financegenie.android.domain.TransactionSelection
import com.financegenie.android.domain.buildAnalytics
import com.financegenie.android.domain.BudgetCopyPlan
import com.financegenie.android.domain.BudgetProgress
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.LedgerReport
import com.financegenie.android.domain.MonthlyBudget
import com.financegenie.android.domain.planBudgetCopy
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
    val budgets: List<BudgetRow> = emptyList(),
    val analytics: LedgerAnalytics? = null,
    @StringRes val error: Int? = null,
)

data class LedgerNotice(val id: UUID = UUID.randomUUID(), @StringRes val message: Int, val undoGroup: UUID? = null)
data class BudgetRow(val record: StoredRecord<MonthlyBudget>, val progress: BudgetProgress)
data class BudgetCopyConfirmation(val plan: BudgetCopyPlan, val expectedLedgerRevision: Long)
private data class LoadResult(val ledger: StoredLedger? = null, val failed: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(private val repository: LedgerRepository, private val clock: Clock) : ViewModel() {
    val csv = CsvImportController(repository, clock, viewModelScope)
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
                val snapshot = ledger.snapshot
                val today = LedgerDates.today(clock)
                val search = selection.search.trim()
                val scope = TransactionSelection(selection.accountId, selection.categoryId,
                    if (search.isEmpty()) null else ledger.transactions.filter {
                        it.details.merchant.contains(search, true) || it.details.notes.contains(search, true)
                    }.map { it.transaction.id }.toSet())
                val window = AnalyticsWindow.forSelection(snapshot, selection.month, selection.allDates, today)
                val fullMonth = ReportRequest(DateRange.month(selection.month))
                val currentMonth = ReportRequest(DateRange.month(YearMonth.from(today)))
                val scoped = ReportRequest(window.period, scope)
                val daily = ReportRequest(window.daily, scope)
                val monthly = ReportRequest(window.monthly, scope)
                val requests = listOf(fullMonth, currentMonth, scoped, daily, monthly).distinct()
                val reports = requests.zip(calculator.reports(snapshot, requests)).toMap()
                val report = reports.getValue(fullMonth)
                val current = reports.getValue(currentMonth)
                val analytics = buildAnalytics(snapshot, reports.getValue(scoped), reports.getValue(daily), reports.getValue(monthly))
                val observations = ledger.observations.groupBy { it.value.accountId }.mapValues { (_, entries) ->
                    val latest = entries.maxWith(compareBy({ it.value.asOfDate }, { it.metadata.createdAtMillis }, { it.value.id }))
                    calculator.reconcile(ledger.snapshot, latest.value.id)
                }
                val rows = ledger.transactions.filter { record ->
                    val event = record.transaction
                    (selection.showDeleted || !event.isDeleted) &&
                        (selection.allDates || event.effectiveDate in window.period) && scope.includes(event)
                }.sortedWith(compareByDescending<TransactionRecord> { it.transaction.effectiveDate }
                    .thenByDescending { it.metadata.createdAtMillis }.thenBy { it.transaction.id })
                val budgets = ledger.budgets.filter { it.value.month == selection.month }.sortedBy { it.value.categoryId.orEmpty() }
                    .map { BudgetRow(it, calculator.budgetProgress(it.value, report)) }
                LedgerUiState(selection, false, ledger, report, current, observations, rows, budgets, analytics)
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
    private val mutableBudgetCopy = MutableStateFlow<BudgetCopyConfirmation?>(null)
    val budgetCopy = mutableBudgetCopy.asStateFlow()
    private val mutableBudgetRemoval = MutableStateFlow<StoredRecord<MonthlyBudget>?>(null)
    val budgetRemoval = mutableBudgetRemoval.asStateFlow()
    private val mutableBudgetError = MutableStateFlow<Int?>(null)
    val budgetError = mutableBudgetError.asStateFlow()

    fun retry() { reload.value += 1 }
    fun filter(change: (LedgerFilters) -> LedgerFilters) {
        filters.value = change(filters.value).let { it.copy(search = it.search.take(200)) }
    }
    fun moveMonth(delta: Long) {
        val month = filters.value.month.plusMonths(delta)
        if (month.year in 1..9999) filter { it.copy(month = month, allDates = false) }
    }

    fun newBudget(categoryId: String? = null) = open(BudgetDraft(UUID.randomUUID(), month = filters.value.month.toString(), categoryId = categoryId))
    fun editBudget(record: StoredRecord<MonthlyBudget>) = open(BudgetDraft(record.value.id, record,
        record.value.month.toString(), record.value.categoryId, inputMagnitude(record.value.limitMinor)))

    fun askCopyBudgets() {
        if (busy.value) return
        val ledger = state.value.ledger ?: return
        if (state.value.filters.month == YearMonth.of(9999, 12)) return
        mutableBudgetError.value = null
        mutableBudgetCopy.value = BudgetCopyConfirmation(planBudgetCopy(ledger.snapshot, state.value.filters.month), ledger.settings.revision)
    }
    fun cancelBudgetCopy() { if (!busy.value) mutableBudgetCopy.value = null }
    fun confirmBudgetCopy() {
        val confirmation = budgetCopy.value ?: return
        work(budgetAction = true) {
            repository.copyBudgetsToNextMonth(confirmation.plan.fromMonth, confirmation.expectedLedgerRevision)
            filter { it.copy(month = confirmation.plan.toMonth, allDates = false) }
            mutableBudgetCopy.value = null
            mutableNotice.value = LedgerNotice(message = R.string.budgets_copied)
        }
    }
    fun askRemoveBudget(record: StoredRecord<MonthlyBudget>) {
        if (busy.value) return
        mutableBudgetError.value = null
        mutableBudgetRemoval.value = record
    }
    fun cancelBudgetRemoval() { if (!busy.value) mutableBudgetRemoval.value = null }
    fun confirmBudgetRemoval() {
        val record = budgetRemoval.value ?: return
        work(budgetAction = true) {
            repository.removeBudget(record.value.id, record.metadata.revision)
            mutableBudgetRemoval.value = null
            mutableNotice.value = LedgerNotice(message = R.string.budget_removed)
        }
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
                is BudgetDraft -> {
                    val budget = draft.budget()
                    repository.saveBudget(budget, draft.original?.metadata?.revision)
                    filter { it.copy(month = budget.month, allDates = false) }
                    LedgerNotice(message = R.string.budget_saved)
                }
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

    private fun work(form: Boolean = false, budgetAction: Boolean = false, action: suspend () -> Unit) {
        // Set synchronously before launching: two taps in the same frame cannot enqueue two writes.
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableFormError.value = null
        mutableBudgetError.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = errorMessage(error, editingOpening = form && editor.value is AccountDraft)
                when {
                    form -> mutableFormError.value = message
                    budgetAction -> mutableBudgetError.value = if (error is StaleRecordException) R.string.error_budget_changed else message
                    else -> mutableNotice.value = LedgerNotice(message = message)
                }
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
        "Cannot select an archived budget category" -> R.string.error_archived_category
        "A month/category scope can have only one budget" -> R.string.error_budget_exists
        "Transaction predates account opening", "Transfer predates destination account opening" ->
            if (editingOpening) R.string.error_opening_history else R.string.error_opening_date
        "Observation must be within the recorded account lifetime" -> R.string.error_observation_date
        else -> R.string.error_save
    }
}
