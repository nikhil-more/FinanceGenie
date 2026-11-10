package com.financegenie.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financegenie.android.R
import com.financegenie.android.csv.*
import com.financegenie.android.data.StoredLedger
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.domain.TransactionKind
import java.time.Instant
import java.util.UUID

@Composable internal fun CsvImportScreen(ledger: StoredLedger, controller: CsvImportController, editTransaction: (TransactionRecord) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.read { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException() }
    }
    BackHandler(state.document != null || state.busy) { if (state.canCancel) controller.cancel() }
    var batchDetails by rememberSaveable { mutableStateOf<String?>(null) }
    var bulkConfirmation by rememberSaveable { mutableStateOf(false) }
    val plan = state.plan
    val stale = state.request?.expectedLedgerRevision?.let { it != ledger.settings.revision } == true
    LazyColumn(Modifier.fillMaxSize().testTag("csv_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.csv_import), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.csv_help))
            Text(stringResource(R.string.csv_privacy))
            Button(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("csv_choose_file")) { Text(stringResource(R.string.csv_choose)) }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (state.canCancel) R.string.csv_working else R.string.csv_atomic_work))
            }
            state.message?.let { Text(stringResource(csvMessage(it)), modifier = Modifier.testTag("csv_message")) }
            state.readProblem?.let { Text(stringResource(csvFileProblem(it))) }
            if (stale) Text(stringResource(R.string.csv_stale))
            if (state.document != null || state.work == CsvWork.READING) TextButton(onClick = controller::cancel,
                enabled = state.canCancel, modifier = Modifier.testTag("csv_cancel")) { Text(stringResource(R.string.csv_cancel)) }
        }
        if (state.document != null && plan == null) item { CsvMappingFields(state, ledger, controller) }
        if (plan != null) {
            item {
                Text(stringResource(R.string.csv_classify_help))
                Text(stringResource(R.string.csv_count_parsed, plan.rows.size), Modifier.testTag("csv_count_parsed"))
                Text(stringResource(R.string.csv_count_invalid, plan.invalidCount), Modifier.testTag("csv_count_invalid"))
                Text(stringResource(R.string.csv_count_matches, plan.duplicateCount), Modifier.testTag("csv_count_matches"))
                Text(stringResource(R.string.csv_count_review, plan.reviewCount), Modifier.testTag("csv_count_review"))
                Text(stringResource(R.string.csv_count_accepted, plan.acceptedCount), Modifier.testTag("csv_count_accepted"))
                Text(stringResource(R.string.csv_count_excluded, plan.excludedCount), Modifier.testTag("csv_count_excluded"))
                Text(stringResource(R.string.csv_count_blanks, state.document!!.blankLines))
                Text(stringResource(R.string.csv_totals, money(plan.debitMinor), money(plan.creditMinor)))
                Text(stringResource(R.string.csv_accepted_totals, money(plan.acceptedDebitMinor), money(plan.acceptedCreditMinor)))
                if (plan.blockingError) Text(stringResource(R.string.csv_blocking))
                Button(onClick = { bulkConfirmation = true }, enabled = !state.busy && plan.replay == null && !stale,
                    modifier = Modifier.fillMaxWidth().testTag("csv_bulk_debits")) { Text(stringResource(R.string.csv_bulk_debits)) }
                Button(onClick = controller::askCommit, enabled = !state.busy && plan.canCommit && !stale,
                    modifier = Modifier.fillMaxWidth().testTag("csv_commit")) { Text(stringResource(R.string.csv_commit)) }
                TextButton(onClick = controller::preview, enabled = !state.busy, modifier = Modifier.testTag("csv_refresh")) { Text(stringResource(R.string.csv_refresh)) }
                TextButton(onClick = controller::editMapping, enabled = !state.busy, modifier = Modifier.testTag("csv_edit_mapping")) { Text(stringResource(R.string.csv_columns)) }
            }
            items(plan.rows, key = { it.parsed.number }) { row ->
                Card(Modifier.fillMaxWidth().testTag("csv_row_${row.parsed.number}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.csv_row, row.parsed.number), style = MaterialTheme.typography.titleMedium)
                        if (row.parsed.problem != null) Text(stringResource(R.string.csv_invalid_row, stringResource(csvRowProblem(row.parsed.problem))))
                        else {
                            Text("${row.parsed.date} · ${row.parsed.direction} · ${money(row.parsed.amountMinor!!)}")
                            Text(row.parsed.merchant)
                            Text(stringResource(if (row.accepted) R.string.csv_accepted else if (row.decision.action == CsvAction.EXCLUDE) R.string.csv_excluded else R.string.csv_review))
                            if (row.matches.isNotEmpty()) Text(stringResource(R.string.csv_duplicate_help))
                            TextButton(onClick = { controller.openReview(row.parsed.number) }, enabled = !state.busy && plan.replay == null && !stale,
                                modifier = Modifier.testTag("csv_review_${row.parsed.number}")) { Text(stringResource(R.string.csv_review_row, row.parsed.number)) }
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text(stringResource(R.string.csv_history), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.csv_undo_help))
            if (ledger.importBatches.isEmpty()) Text(stringResource(R.string.csv_history_empty))
        }
        items(ledger.importBatches, key = { "batch_${it.id}" }) { batch ->
            Card(Modifier.fillMaxWidth().testTag("csv_batch_${batch.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ledger.accounts.first { it.value.id == batch.accountId }.value.name)
                    Text(Instant.ofEpochMilli(batch.createdAtMillis).atZone(com.financegenie.android.domain.LedgerDates.reportingZone).toLocalDate().toString())
                    Text(stringResource(R.string.csv_history_count, batch.acceptedCount))
                    Text(stringResource(if (batch.isUndone) R.string.csv_batch_undone else R.string.csv_batch_active))
                    TextButton(onClick = { batchDetails = batch.id.toString() }, enabled = !state.busy,
                        modifier = Modifier.testTag("csv_records_${batch.id}")) { Text(stringResource(R.string.csv_records)) }
                    Button(onClick = { controller.askBatch(if (batch.isUndone) CsvBatchAction.RESTORE else CsvBatchAction.UNDO, batch, ledger.settings.revision) },
                        enabled = !state.busy, modifier = Modifier.testTag("csv_${if (batch.isUndone) "restore" else "undo"}_${batch.id}")) {
                        Text(stringResource(if (batch.isUndone) R.string.csv_restore else R.string.csv_undo))
                    }
                }
            }
        }
    }
    if (bulkConfirmation) AlertDialog(onDismissRequest = { bulkConfirmation = false }, title = { Text(stringResource(R.string.csv_bulk_title)) },
        text = { Text(stringResource(R.string.csv_bulk_help)) }, confirmButton = { TextButton(onClick = { bulkConfirmation = false; controller.confirmClearDebits() }, modifier = Modifier.testTag("csv_confirm_bulk")) { Text(stringResource(R.string.csv_confirm)) } },
        dismissButton = { TextButton(onClick = { bulkConfirmation = false }) { Text(stringResource(R.string.cancel)) } })
    state.reviewing?.let { number -> plan?.rows?.firstOrNull { it.parsed.number == number }?.let { row ->
        CsvRowReview(row, ledger, state.accountId!!, state.reviewDraft!!, controller::review,
            { controller.decide(number, it) }, controller::closeReview)
    } }
    batchDetails?.let { id ->
        val ids = ledger.evidence.filter { it.input.importBatchId.toString() == id }.map { it.input.transactionId }.toSet()
        val records = ledger.transactions.filter { it.transaction.id in ids }
        AlertDialog(onDismissRequest = { batchDetails = null }, title = { Text(stringResource(R.string.csv_records)) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records, key = { it.transaction.id }) { record ->
                    Text("${record.transaction.effectiveDate} · ${money(record.transaction.amountMinor)} · ${record.details.merchant}")
                    Text(stringResource(transactionLabel(record.transaction.kind)))
                    if (record.transaction.isDeleted) Text(stringResource(R.string.deleted))
                    TextButton(onClick = { editTransaction(record) }, enabled = !record.transaction.isDeleted) { Text(stringResource(R.string.edit)) }
                }
            } }, confirmButton = { TextButton(onClick = { batchDetails = null }) { Text(stringResource(R.string.back)) } })
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(onDismissRequest = controller::dismissConfirmation,
            title = { Text(stringResource(when (confirmation.action) { CsvBatchAction.COMMIT -> R.string.csv_confirm_title; CsvBatchAction.UNDO -> R.string.csv_undo; CsvBatchAction.RESTORE -> R.string.csv_restore })) },
            text = { Text(if (confirmation.action == CsvBatchAction.COMMIT) stringResource(R.string.csv_confirm_details,
                ledger.accounts.first { it.value.id == state.accountId }.value.name, plan!!.acceptedCount, plan.writes.size, plan.acceptedCount - plan.writes.size)
                else stringResource(R.string.csv_undo_help)) },
            confirmButton = { TextButton(onClick = controller::confirm, enabled = !state.busy, modifier = Modifier.testTag("csv_confirm_action")) { Text(stringResource(R.string.csv_confirm)) } },
            dismissButton = { TextButton(onClick = controller::dismissConfirmation, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable private fun CsvMappingFields(state: CsvImportState, ledger: StoredLedger, controller: CsvImportController) {
    val document = state.document!!
    val mapping = state.mapping
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceField(stringResource(R.string.account), state.accountId?.toString(), ledger.accounts.filterNot { it.value.isArchived }.map { Choice(it.value.id.toString(), it.value.name) }, "csv_account") { controller.account(UUID.fromString(it)) }
        ChoiceField(stringResource(R.string.csv_mode), mapping.mode.name, listOf(Choice(CsvAmountMode.AMOUNT_DIRECTION.name, stringResource(R.string.csv_mode_single)), Choice(CsvAmountMode.DEBIT_CREDIT_COLUMNS.name, stringResource(R.string.csv_mode_split))), "csv_mode") { mode -> controller.mapping { it.copy(mode = CsvAmountMode.valueOf(mode)) } }
        ChoiceField(stringResource(R.string.csv_date_format), mapping.dateFormat.name, listOf(Choice("ISO", stringResource(R.string.csv_iso)), Choice("DAY_FIRST", stringResource(R.string.csv_dmy)), Choice("MONTH_FIRST", stringResource(R.string.csv_mdy))), "csv_date_format") { format -> controller.mapping { it.copy(dateFormat = CsvDateFormat.valueOf(format)) } }
        val columns = document.headers.mapIndexed { index, header -> Choice(index.toString(), "${index + 1}: $header") }
        @Composable fun column(label: Int, value: Int, name: String, optional: Boolean = false, change: (CsvMapping, Int) -> CsvMapping) {
            ChoiceField(stringResource(label), value.toString(), (if (optional) listOf(Choice("-1", stringResource(R.string.csv_none))) else emptyList()) + columns, "csv_col_$name") { selected -> controller.mapping { change(it, selected.toInt()) } }
        }
        column(R.string.csv_col_date, mapping.date, "date") { m, i -> m.copy(date = i) }
        if (mapping.mode == CsvAmountMode.AMOUNT_DIRECTION) {
            column(R.string.csv_col_amount, mapping.amount, "amount") { m, i -> m.copy(amount = i) }
            column(R.string.csv_col_direction, mapping.direction, "direction") { m, i -> m.copy(direction = i) }
        } else {
            column(R.string.csv_col_debit, mapping.debit, "debit") { m, i -> m.copy(debit = i) }
            column(R.string.csv_col_credit, mapping.credit, "credit") { m, i -> m.copy(credit = i) }
        }
        column(R.string.csv_col_merchant, mapping.merchant, "merchant", true) { m, i -> m.copy(merchant = i) }
        column(R.string.csv_col_category, mapping.category, "category", true) { m, i -> m.copy(category = i) }
        column(R.string.csv_col_reference, mapping.reference, "reference", true) { m, i -> m.copy(reference = i) }
        Button(onClick = controller::preview, enabled = state.accountId != null && !state.busy, modifier = Modifier.fillMaxWidth().testTag("csv_preview")) { Text(stringResource(R.string.csv_preview)) }
    }
}

@Composable private fun CsvRowReview(row: CsvReviewedRow, ledger: StoredLedger, accountId: UUID, decision: CsvDecision,
    update: ((CsvDecision) -> CsvDecision) -> Unit, accept: (CsvDecision) -> Unit, close: () -> Unit) {
    val kinds = if (row.parsed.direction == CsvDirection.DEBIT) listOf(TransactionKind.EXPENSE, TransactionKind.TRANSFER) else listOf(TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.TRANSFER)
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.csv_review_row, row.parsed.number)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${row.parsed.date} · ${money(row.parsed.amountMinor!!)} · ${row.parsed.merchant}")
            if (row.parsed.categoryId == null) Text(stringResource(R.string.csv_unknown_category))
            if (row.matches.isNotEmpty()) {
                Text(stringResource(R.string.csv_duplicate_help))
                ChoiceField(stringResource(R.string.csv_match), decision.existingId?.toString(), row.matches.map { record ->
                    val event = record.transaction
                    Choice(event.id.toString(), "${event.effectiveDate} · ${money(event.amountMinor)} · ${stringResource(transactionLabel(event.kind))} · ${record.details.merchant} · ${event.id.toString().take(8)}${if (event.isDeleted) " · ${stringResource(R.string.deleted)}" else ""}")
                }, "csv_existing") { selected -> update { it.copy(existingId = UUID.fromString(selected)) } }
                TextButton(onClick = { accept(decision.copy(action = CsvAction.ATTACH)) }, enabled = row.matches.any { it.transaction.id == decision.existingId && !it.transaction.isDeleted }, modifier = Modifier.testTag("csv_attach")) { Text(stringResource(R.string.csv_match_action)) }
                ToggleRow(R.string.csv_keep_separate, decision.duplicateOverride, "csv_duplicate_override") { value -> update { it.copy(duplicateOverride = value) } }
            }
            ChoiceField(stringResource(R.string.csv_classification), decision.kind.name, kinds.map { Choice(it.name, stringResource(transactionLabel(it))) }, "csv_kind") { value -> update { it.copy(kind = TransactionKind.valueOf(value), originalExpenseId = null, confirmedUnlinked = false) } }
            if (decision.kind in setOf(TransactionKind.EXPENSE, TransactionKind.REFUND)) ChoiceField(stringResource(R.string.category), decision.categoryId,
                ledger.categories.filterNot { it.value.isArchived }.map { Choice(it.value.id, it.value.name) }, "csv_category") { value -> update { it.copy(categoryId = value) } }
            if (decision.kind == TransactionKind.TRANSFER) ChoiceField(stringResource(R.string.csv_other_account), decision.otherAccountId?.toString(),
                ledger.accounts.filter { !it.value.isArchived && it.value.id != accountId }.map { Choice(it.value.id.toString(), it.value.name) }, "csv_other_account") { value -> update { it.copy(otherAccountId = UUID.fromString(value)) } }
            if (decision.kind == TransactionKind.REFUND) {
                ChoiceField(stringResource(R.string.original_purchase), decision.originalExpenseId?.toString() ?: "unlinked",
                    listOf(Choice("unlinked", stringResource(R.string.unlinked_refund))) + ledger.transactions.filter { !it.transaction.isDeleted && it.transaction.kind == TransactionKind.EXPENSE }.map {
                        Choice(it.transaction.id.toString(), "${it.transaction.effectiveDate} · ${money(it.transaction.amountMinor)} · ${it.details.merchant}")
                    }, "csv_original") { value -> update { it.copy(originalExpenseId = value.takeUnless { it == "unlinked" }?.let(UUID::fromString), confirmedUnlinked = false) } }
                if (decision.originalExpenseId == null) ToggleRow(R.string.confirm_unlinked_refund, decision.confirmedUnlinked, "csv_confirm_unlinked") { value -> update { it.copy(confirmedUnlinked = value) } }
            }
        }
    }, confirmButton = { TextButton(onClick = { accept(decision.copy(action = CsvAction.CREATE)) },
        enabled = (row.matches.isEmpty() || decision.duplicateOverride) && (decision.kind != TransactionKind.TRANSFER || decision.otherAccountId != null) &&
            (decision.kind != TransactionKind.REFUND || decision.originalExpenseId != null || decision.confirmedUnlinked), modifier = Modifier.testTag("csv_accept_row")) { Text(stringResource(R.string.csv_create)) } },
        dismissButton = { TextButton(onClick = { accept(CsvDecision(CsvAction.EXCLUDE)) }, modifier = Modifier.testTag("csv_exclude_row")) { Text(stringResource(R.string.csv_exclude)) } })
}

internal fun csvMessage(message: CsvMessage): Int = when (message) {
    CsvMessage.READ_FAILED -> R.string.csv_read_failed; CsvMessage.MAPPING -> R.string.csv_mapping_error
    CsvMessage.INVALID_SELECTION -> R.string.csv_selection_error; CsvMessage.STALE -> R.string.csv_stale
    CsvMessage.REPLAY -> R.string.csv_replay; CsvMessage.UNDONE_REPLAY -> R.string.csv_undone_replay
    CsvMessage.CONFLICT -> R.string.csv_conflict; CsvMessage.SAVED -> R.string.csv_saved
    CsvMessage.UNDONE -> R.string.csv_undone; CsvMessage.RESTORED -> R.string.csv_restored
}
private fun csvRowProblem(problem: CsvRowProblem): Int = when (problem) {
    CsvRowProblem.COLUMN_COUNT -> R.string.csv_problem_columns; CsvRowProblem.DATE -> R.string.csv_problem_date
    CsvRowProblem.AMOUNT -> R.string.csv_problem_amount; CsvRowProblem.DIRECTION -> R.string.csv_problem_direction
    CsvRowProblem.FUTURE_DATE -> R.string.csv_problem_future; CsvRowProblem.BEFORE_OPENING -> R.string.csv_problem_opening
    CsvRowProblem.TEXT_LENGTH -> R.string.csv_problem_text
}
private fun csvFileProblem(problem: CsvProblem): Int = when (problem) {
    CsvProblem.TOO_LARGE -> R.string.csv_file_size
    CsvProblem.TOO_MANY_ROWS -> R.string.csv_file_rows
    CsvProblem.TOO_MANY_COLUMNS -> R.string.csv_file_columns
    CsvProblem.FIELD_TOO_LONG -> R.string.csv_file_field
    CsvProblem.INVALID_UTF8 -> R.string.csv_file_utf8
    CsvProblem.MALFORMED_QUOTES -> R.string.csv_file_quotes
    CsvProblem.INVALID_HEADER -> R.string.csv_file_header
    else -> R.string.csv_mapping_error
}
