package com.financegenie.android.ui

import com.financegenie.android.csv.*
import com.financegenie.android.data.CsvReplayException
import com.financegenie.android.data.CsvUndoConflict
import com.financegenie.android.data.ImportBatchRecord
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.StaleRecordException
import com.financegenie.android.domain.TransactionKind
import java.io.InputStream
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

enum class CsvWork { IDLE, READING, PREPARING, COMMITTING, CHANGING_BATCH }
enum class CsvMessage { READ_FAILED, MAPPING, INVALID_SELECTION, STALE, REPLAY, UNDONE_REPLAY, CONFLICT, SAVED, UNDONE, RESTORED }
enum class CsvBatchAction { COMMIT, UNDO, RESTORE }
data class CsvConfirmation(val action: CsvBatchAction, val revision: Long, val batch: ImportBatchRecord? = null)
data class CsvImportState(val document: CsvDocument? = null, val mapping: CsvMapping = CsvMapping(), val accountId: UUID? = null,
    val request: CsvImportRequest? = null, val plan: CsvImportPlan? = null, val work: CsvWork = CsvWork.IDLE,
    val message: CsvMessage? = null, val readProblem: CsvProblem? = null, val confirmation: CsvConfirmation? = null,
    val reviewing: Int? = null, val reviewDraft: CsvDecision? = null) {
    val busy get() = work != CsvWork.IDLE
    val canCancel get() = work !in setOf(CsvWork.COMMITTING, CsvWork.CHANGING_BATCH)
}

/** Shares the owning ledger ViewModel's lifetime; source/review data is never persisted. */
class CsvImportController(private val repository: LedgerRepository, private val clock: Clock, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(CsvImportState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun read(open: () -> InputStream) {
        if (state.value.busy) return
        mutableState.value = CsvImportState(work = CsvWork.READING)
        job = scope.launch {
            try {
                val document = runInterruptible(Dispatchers.IO) { open().use(CsvReader::read) }
                mutableState.value = CsvImportState(document, CsvMapping.forHeaders(document.headers))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = CsvImportState(message = CsvMessage.READ_FAILED, readProblem = (error as? CsvFailure)?.reason)
            }
        }
    }
    fun cancel() {
        if (!state.value.canCancel) return
        job?.cancel()
        mutableState.value = CsvImportState()
    }
    fun mapping(change: (CsvMapping) -> CsvMapping) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(mapping = change(state.value.mapping), plan = null, request = null, message = null, reviewing = null, reviewDraft = null)
    }
    fun account(id: UUID) {
        if (!state.value.busy) mutableState.value = state.value.copy(accountId = id, plan = null, request = null, message = null, reviewing = null, reviewDraft = null)
    }
    fun editMapping() {
        if (!state.value.busy) mutableState.value = state.value.copy(plan = null, request = null, message = null, reviewing = null, reviewDraft = null)
    }
    fun preview() {
        val current = state.value.copy(reviewing = null, reviewDraft = null)
        val document = current.document ?: return
        val account = current.accountId ?: return
        if (current.busy) return
        mutableState.value = current.copy(work = CsvWork.PREPARING, message = null, confirmation = null)
        job = scope.launch {
            try {
                val ledger = repository.read()
                val request = CsvImportRequest(document, current.mapping, account, emptyMap(), ledger.settings.revision)
                val plan = withContext(Dispatchers.Default) { planCsvImport(ledger, request, clock) }
                mutableState.value = current.copy(request = request, plan = plan,
                    message = plan.replay?.let { if (it.isUndone) CsvMessage.UNDONE_REPLAY else CsvMessage.REPLAY })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = current.copy(message = CsvMessage.MAPPING)
            }
        }
    }
    fun decide(row: Int, decision: CsvDecision) = revise { it + (row to decision) }
    fun openReview(number: Int) {
        if (state.value.busy) return
        val row = state.value.plan?.rows?.find { it.parsed.number == number && it.parsed.problem == null } ?: return
        mutableState.value = state.value.copy(reviewing = number, reviewDraft = row.decision.copy(
            kind = if (row.decision.action == CsvAction.REVIEW && row.parsed.direction == CsvDirection.CREDIT) TransactionKind.INCOME else row.decision.kind,
            categoryId = row.decision.categoryId ?: row.parsed.categoryId ?: com.financegenie.android.domain.UNCATEGORIZED_ID))
    }
    fun review(change: (CsvDecision) -> CsvDecision) {
        if (!state.value.busy) state.value.reviewDraft?.let { mutableState.value = state.value.copy(reviewDraft = change(it)) }
    }
    fun closeReview() { if (!state.value.busy) mutableState.value = state.value.copy(reviewing = null, reviewDraft = null) }
    fun confirmClearDebits() = revise { choices ->
        choices + state.value.plan!!.rows.filter {
            it.parsed.problem == null && it.parsed.direction == CsvDirection.DEBIT && it.parsed.categoryId != null && it.matches.isEmpty() && it.decision.action == CsvAction.REVIEW
        }.associate { it.parsed.number to CsvDecision(CsvAction.CREATE, TransactionKind.EXPENSE, it.parsed.categoryId) }
    }
    private fun revise(change: (Map<Int, CsvDecision>) -> Map<Int, CsvDecision>) {
        val current = state.value.copy(reviewing = null, reviewDraft = null)
        val request = current.request ?: return
        if (current.busy || current.plan?.replay != null) return
        val updated = request.copy(decisions = change(request.decisions))
        mutableState.value = current.copy(work = CsvWork.PREPARING, message = null, confirmation = null)
        job = scope.launch {
            try {
                val ledger = repository.read()
                if (ledger.settings.revision != request.expectedLedgerRevision) throw StaleRecordException()
                val plan = withContext(Dispatchers.Default) { planCsvImport(ledger, updated, clock) }
                mutableState.value = current.copy(request = updated, plan = plan)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = current.copy(message = if (error is StaleRecordException) CsvMessage.STALE else CsvMessage.INVALID_SELECTION)
            }
        }
    }
    fun askCommit() {
        val current = state.value
        if (!current.busy && current.plan?.canCommit == true) mutableState.value = current.copy(
            confirmation = CsvConfirmation(CsvBatchAction.COMMIT, current.request!!.expectedLedgerRevision))
    }
    fun askBatch(action: CsvBatchAction, batch: ImportBatchRecord, revision: Long) {
        if (!state.value.busy) mutableState.value = state.value.copy(confirmation = CsvConfirmation(action, revision, batch), message = null)
    }
    fun dismissConfirmation() { if (!state.value.busy) mutableState.value = state.value.copy(confirmation = null) }
    fun confirm() {
        val current = state.value
        val confirmation = current.confirmation ?: return
        if (current.busy) return
        mutableState.value = current.copy(work = if (confirmation.action == CsvBatchAction.COMMIT) CsvWork.COMMITTING else CsvWork.CHANGING_BATCH, message = null)
        job = scope.launch {
            try {
                when (confirmation.action) {
                    CsvBatchAction.COMMIT -> repository.commitCsv(requireNotNull(current.request))
                    CsvBatchAction.UNDO -> repository.undoCsv(confirmation.batch!!.id, confirmation.revision)
                    CsvBatchAction.RESTORE -> repository.restoreCsv(confirmation.batch!!.id, confirmation.revision)
                }
                mutableState.value = CsvImportState(message = when (confirmation.action) {
                    CsvBatchAction.COMMIT -> CsvMessage.SAVED
                    CsvBatchAction.UNDO -> CsvMessage.UNDONE
                    CsvBatchAction.RESTORE -> CsvMessage.RESTORED
                })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = current.copy(confirmation = null, message = when (error) {
                    is StaleRecordException -> CsvMessage.STALE
                    is CsvReplayException -> if (error.batch.isUndone) CsvMessage.UNDONE_REPLAY else CsvMessage.REPLAY
                    is CsvUndoConflict -> CsvMessage.CONFLICT
                    else -> CsvMessage.INVALID_SELECTION
                })
            }
        }
    }
}
