package com.financegenie.android.ui

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financegenie.android.R

@Composable internal fun RecoveryScreen(controller: RecoveryController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    fun save(action: RecoveryAction, uri: android.net.Uri?) {
        if (uri != null) controller.export(action, { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() },
            { DocumentsContract.deleteDocument(resolver, uri) })
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { save(RecoveryAction.BACKUP, it) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { save(RecoveryAction.CSV, it) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.read { resolver.openInputStream(uri) ?: throw java.io.IOException() }
    }
    BackHandler(state.preview != null || state.busy) { if (state.canCancel) controller.cancel() }
    LazyColumn(Modifier.fillMaxSize().testTag("recovery_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.recovery_help))
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(if (state.canCancel) R.string.recovery_working else R.string.recovery_replacing)) }
            state.message?.let { Text(stringResource(it), Modifier.testTag("recovery_message")) }
        }
        if (state.preview == null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { controller.ask(RecoveryAction.BACKUP) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("recovery_backup")) { Text(stringResource(R.string.recovery_backup)) }
                Button(onClick = { restorePicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("recovery_choose_restore")) { Text(stringResource(R.string.recovery_choose_restore)) }
                OutlinedButton(onClick = { controller.ask(RecoveryAction.CSV) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("recovery_csv")) { Text(stringResource(R.string.recovery_csv)) }
                Text(stringResource(R.string.recovery_csv_help))
                HorizontalDivider()
                Text(stringResource(R.string.recovery_delete_help))
                OutlinedButton(onClick = { controller.ask(RecoveryAction.DELETE) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("recovery_delete")) { Text(stringResource(R.string.recovery_delete)) }
            }
        }
        state.preview?.let { preview -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_validated), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("recovery_validated"))
                Text(stringResource(R.string.recovery_counts, preview.ledger.accounts.size, preview.ledger.categories.size, preview.ledger.transactions.size,
                    preview.ledger.budgets.size, preview.ledger.observations.size, preview.ledger.importBatches.size))
                Text(stringResource(R.string.recovery_restore_help))
                Button(onClick = controller::restore, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("recovery_restore")) { Text(stringResource(R.string.recovery_restore)) }
            }
        } }
        if (state.preview != null || state.busy) item {
            TextButton(onClick = controller::cancel, enabled = state.canCancel, modifier = Modifier.testTag("recovery_cancel")) { Text(stringResource(R.string.cancel)) }
        }
    }
    state.confirmation?.let { action ->
        AlertDialog(onDismissRequest = controller::dismiss, title = { Text(stringResource(if (action == RecoveryAction.DELETE) R.string.recovery_delete else R.string.recovery_plaintext_title)) },
            text = { Text(stringResource(when (action) { RecoveryAction.BACKUP -> R.string.recovery_plaintext; RecoveryAction.CSV -> R.string.recovery_csv_disclosure; RecoveryAction.DELETE -> R.string.recovery_delete_help }), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                when (action) {
                    RecoveryAction.DELETE -> controller.delete()
                    RecoveryAction.BACKUP -> { controller.dismiss(); backupPicker.launch("FinanceGenie-backup.json") }
                    RecoveryAction.CSV -> { controller.dismiss(); csvPicker.launch("FinanceGenie-report.csv") }
                }
            }, modifier = Modifier.testTag("recovery_confirm")) { Text(stringResource(if (action == RecoveryAction.DELETE) R.string.recovery_delete_confirm else R.string.recovery_choose_location)) } },
            dismissButton = { TextButton(onClick = controller::dismiss) { Text(stringResource(R.string.cancel)) } })
    }
}
