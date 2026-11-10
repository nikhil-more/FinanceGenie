package com.financegenie.android.ui

import com.financegenie.android.R
import com.financegenie.android.data.LedgerSettings
import com.financegenie.android.data.StaleRecordException
import com.financegenie.android.recovery.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecoveryAction { BACKUP, CSV, DELETE }
data class RecoveryState(val busy: Boolean = false, val canCancel: Boolean = true,
    val preview: PortableBackup? = null, val expected: LedgerSettings? = null,
    val confirmation: RecoveryAction? = null, val message: Int? = null)

class RecoveryController(private val profiles: LocalProfileStore, private val clock: Clock, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(RecoveryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun cancel() { if (state.value.canCancel) { job?.cancel(); mutable.value = RecoveryState() } }
    fun dismiss() { if (!state.value.busy) mutable.value = state.value.copy(confirmation = null) }
    fun ask(action: RecoveryAction) {
        if (state.value.busy) return
        if (action != RecoveryAction.DELETE) { mutable.value = state.value.copy(confirmation = action, message = null); return }
        work { mutable.value = RecoveryState(expected = profiles.session.value.repository.read().settings, confirmation = action) }
    }
    fun export(action: RecoveryAction, open: () -> OutputStream, removeIncomplete: () -> Unit = {}) {
        require(action != RecoveryAction.DELETE)
        work {
            val backup = profiles.backup()
            try {
                runInterruptible(Dispatchers.IO) { open().use { output ->
                    if (action == RecoveryAction.BACKUP) BackupCodec.write(backup, output, clock) else CsvReport.write(backup.ledger, output)
                } }
            } catch (e: Exception) { withContext(NonCancellable + Dispatchers.IO) { runCatching(removeIncomplete) }; throw e }
            mutable.value = RecoveryState(message = R.string.recovery_exported)
        }
    }
    fun read(open: () -> InputStream) = work {
        val backup = runInterruptible(Dispatchers.IO) { open().use { BackupCodec.read(it, clock) } }
        val expected = profiles.session.value.repository.read().settings
        mutable.value = RecoveryState(preview = backup, expected = expected)
    }
    fun restore() {
        val current = state.value
        val preview = current.preview ?: return
        val expected = current.expected ?: return
        work(canCancel = false) { profiles.restore(preview, expected); mutable.value = RecoveryState(message = R.string.recovery_restored) }
    }
    fun delete() {
        val current = state.value
        if (current.confirmation != RecoveryAction.DELETE) return
        val expected = current.expected ?: return
        work(canCancel = false) { profiles.deleteAll(expected); mutable.value = RecoveryState(message = R.string.recovery_deleted) }
    }
    private fun work(canCancel: Boolean = true, action: suspend () -> Unit) {
        if (state.value.busy) return
        val previous = state.value
        mutable.value = previous.copy(busy = true, canCancel = canCancel, confirmation = null, message = null)
        job = scope.launch {
            try { action() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = previous.copy(busy = false, canCancel = true, confirmation = null,
                    message = if (e is StaleRecordException) R.string.recovery_stale else R.string.recovery_failed)
            }
        }
    }
}
