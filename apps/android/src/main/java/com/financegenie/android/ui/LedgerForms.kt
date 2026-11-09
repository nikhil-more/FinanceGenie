package com.financegenie.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.financegenie.android.R
import com.financegenie.android.data.StoredLedger
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.AdjustmentDirection
import com.financegenie.android.domain.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class Choice(val id: String, val label: String)

@Composable
internal fun ChoiceField(label: String, value: String?, choices: List<Choice>, tag: String, onChange: (String) -> Unit) {
    var opened by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    OutlinedButton(onClick = { search = ""; opened = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(choices.firstOrNull { it.id == value }?.label ?: stringResource(R.string.choose))
        }
    }
    if (opened) AlertDialog(
        onDismissRequest = { opened = false },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_choices)) }, singleLine = true)
                val matches = choices.filter { it.label.contains(search, true) }
                if (matches.isEmpty()) Text(stringResource(R.string.no_choices))
                LazyColumn {
                    items(matches, key = { it.id }) { choice ->
                        TextButton(onClick = { onChange(choice.id); opened = false }, modifier = Modifier.fillMaxWidth().testTag("choice_${choice.id}")) {
                            Text(choice.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun FormField(value: String, onChange: (String) -> Unit, @StringRes label: Int, tag: String,
    decimal: Boolean = false, multiline: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled,
        label = { Text(stringResource(label)) }, singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: String, onChange: (String) -> Unit, @StringRes label: Int) {
    var picking by rememberSaveable { mutableStateOf(false) }
    FormField(value, onChange, label, "field_date")
    TextButton(onClick = { picking = true }, modifier = Modifier.testTag("pick_date")) { Text(stringResource(R.string.pick_date)) }
    if (picking) {
        val initial = runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                picking = false
            }) { Text(stringResource(R.string.save)) }
        }, dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } }) {
            DatePicker(picker)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedgerForm(draft: LedgerEditor, ledger: StoredLedger, error: Int?, busy: Boolean,
    update: (LedgerEditor) -> Unit, save: () -> Unit, close: () -> Unit) {
    val title = when (draft) {
        is BudgetDraft -> if (draft.original == null) R.string.add_budget else R.string.edit_budget
        is AccountDraft -> if (draft.expectedRevision == null) R.string.add_account else R.string.edit_account
        is CategoryDraft -> if (draft.expectedRevision == null) R.string.add_category else R.string.edit_category
        is ObservationDraft -> R.string.record_balance
        is TransactionDraft -> if (draft.original == null) R.string.add_transaction else R.string.edit_transaction
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("ledger_form"),
            topBar = { TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
                TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            }) },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { ErrorText(it) }
                    Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("save_form")) {
                        Text(stringResource(if (busy) R.string.saving else R.string.save))
                    }
                }
            }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (draft) {
                    is BudgetDraft -> {
                        FormField(draft.month, { update(draft.copy(month = it)) }, R.string.budget_month, "field_month", enabled = draft.original == null)
                        if (draft.original != null) {
                            Text(budgetScopeLabel(draft.categoryId, ledger), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.budget_edit_help))
                        } else {
                            val scopes = listOf(Choice("overall", stringResource(R.string.budget_overall))) +
                                ledger.categories.filterNot { it.value.isArchived }.map { Choice("category:${it.value.id}", it.value.name) }
                            ChoiceField(stringResource(R.string.budget_scope), budgetScopeKey(draft.categoryId), scopes, "field_budget_scope") {
                                update(draft.copy(categoryId = if (it == "overall") null else it.removePrefix("category:")))
                            }
                        }
                        FormField(draft.amount, { update(draft.copy(amount = it)) }, R.string.budget_limit, "field_amount", decimal = true)
                        Text(stringResource(R.string.budget_zero_help))
                    }
                    is AccountDraft -> {
                        FormField(draft.name, { update(draft.copy(name = it)) }, R.string.account_name, "field_name")
                        ChoiceField(stringResource(R.string.account_type), draft.kind.name, AccountKind.entries.map { Choice(it.name, stringResource(accountLabel(it))) }, "field_kind") {
                            update(draft.copy(kind = AccountKind.valueOf(it)))
                        }
                        FormField(draft.balance, { update(draft.copy(balance = it)) }, R.string.opening_balance, "field_amount", decimal = true)
                        SignField(draft.negative, draft.kind) { update(draft.copy(negative = it)) }
                        DateField(draft.date, { update(draft.copy(date = it)) }, R.string.opening_date)
                    }
                    is CategoryDraft -> FormField(draft.name, { update(draft.copy(name = it)) }, R.string.category_name, "field_name")
                    is ObservationDraft -> {
                        Text(ledger.accounts.first { it.value.id == draft.accountId }.value.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.balance_observation_help))
                        FormField(draft.balance, { update(draft.copy(balance = it)) }, R.string.reported_balance, "field_amount", decimal = true)
                        SignField(draft.negative, draft.accountKind) { update(draft.copy(negative = it)) }
                        DateField(draft.date, { update(draft.copy(date = it)) }, R.string.as_of_date)
                    }
                    is TransactionDraft -> TransactionFields(draft, ledger) { update(it) }
                }
            }
        }
    }
}

@Composable
private fun SignField(negative: Boolean, kind: AccountKind, update: (Boolean) -> Unit) {
    ChoiceField(stringResource(R.string.opening_position), negative.toString(), listOf(
        Choice("false", stringResource(if (kind == AccountKind.CREDIT_CARD) R.string.credit_balance else R.string.positive_balance)),
        Choice("true", stringResource(if (kind == AccountKind.CREDIT_CARD) R.string.amount_owed else R.string.negative_balance)),
    ), "field_sign") { update(it.toBoolean()) }
}

@Composable
private fun TransactionFields(draft: TransactionDraft, ledger: StoredLedger, update: (TransactionDraft) -> Unit) {
    ChoiceField(stringResource(R.string.transaction_type), draft.kind.name,
        TransactionKind.entries.map { Choice(it.name, stringResource(transactionLabel(it))) }, "field_kind") {
        update(draft.copy(kind = TransactionKind.valueOf(it)))
    }
    FormField(draft.amount, { update(draft.copy(amount = it)) }, R.string.amount, "field_amount", decimal = true)
    DateField(draft.date, { update(draft.copy(date = it)) }, R.string.date)
    val original = draft.original?.transaction
    val accounts = ledger.accounts.filter { !it.value.isArchived || it.value.id == original?.accountId || it.value.id == original?.destinationAccountId }
        .map { Choice(it.value.id.toString(), if (it.value.isArchived) stringResource(R.string.archived_name, it.value.name) else it.value.name) }
    ChoiceField(stringResource(if (draft.kind == TransactionKind.TRANSFER) R.string.from_account else R.string.account),
        draft.accountId?.toString(), accounts, "field_account") { update(draft.copy(accountId = java.util.UUID.fromString(it))) }
    if (draft.kind == TransactionKind.TRANSFER) {
        Text(stringResource(R.string.transfer_help))
        ChoiceField(stringResource(R.string.to_account), draft.destinationId?.toString(), accounts.filter { it.id != draft.accountId?.toString() }, "field_destination") {
            update(draft.copy(destinationId = java.util.UUID.fromString(it)))
        }
    }
    if (draft.kind in setOf(TransactionKind.EXPENSE, TransactionKind.REFUND)) {
        val categories = ledger.categories.filter { !it.value.isArchived || it.value.id == original?.categoryId }.map {
            Choice(it.value.id, if (it.value.isArchived) stringResource(R.string.archived_name, it.value.name) else it.value.name)
        }
        ChoiceField(stringResource(R.string.category), draft.categoryId, categories, "field_category") { update(draft.copy(categoryId = it)) }
    }
    if (draft.kind == TransactionKind.REFUND) {
        val expenses = ledger.transactions.filter { !it.transaction.isDeleted && it.transaction.kind == TransactionKind.EXPENSE && it.transaction.id != draft.id }
        val choices = listOf(Choice("unlinked", stringResource(R.string.unlinked_refund))) + expenses.map {
            Choice(it.transaction.id.toString(), "${it.transaction.effectiveDate} · ${it.details.merchant.ifBlank { money(it.transaction.amountMinor) }} · ${money(it.transaction.amountMinor)}")
        }
        ChoiceField(stringResource(R.string.original_purchase), draft.originalExpenseId?.toString() ?: "unlinked", choices, "field_purchase") { choice ->
            val expense = expenses.firstOrNull { it.transaction.id.toString() == choice }?.transaction
            update(draft.copy(originalExpenseId = expense?.id, categoryId = expense?.categoryId ?: draft.categoryId, confirmedUnlinked = false))
        }
        if (draft.originalExpenseId == null) {
            Row(Modifier.fillMaxWidth().testTag("confirm_unlinked").toggleable(draft.confirmedUnlinked,
                role = Role.Checkbox, onValueChange = { update(draft.copy(confirmedUnlinked = it)) })) {
                Checkbox(draft.confirmedUnlinked, onCheckedChange = null)
                Text(stringResource(R.string.confirm_unlinked_refund), modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
    if (draft.kind == TransactionKind.ADJUSTMENT) {
        Text(stringResource(R.string.adjustment_help))
        ChoiceField(stringResource(R.string.adjustment_direction), draft.direction.name, listOf(
            Choice(AdjustmentDirection.INCREASE.name, stringResource(R.string.increase)),
            Choice(AdjustmentDirection.DECREASE.name, stringResource(R.string.decrease)),
        ), "field_direction") { update(draft.copy(direction = AdjustmentDirection.valueOf(it))) }
        FormField(draft.reason, { update(draft.copy(reason = it)) }, R.string.adjustment_reason, "field_reason", multiline = true)
    }
    FormField(draft.merchant, { update(draft.copy(merchant = it)) }, R.string.merchant, "field_merchant")
    FormField(draft.notes, { update(draft.copy(notes = it)) }, R.string.notes, "field_notes", multiline = true)
    Text(stringResource(R.string.notes_private), style = MaterialTheme.typography.bodySmall)
    if (original?.occurredAt != null) Text(stringResource(R.string.date_precision_help), style = MaterialTheme.typography.bodySmall)
}

@StringRes internal fun accountLabel(kind: AccountKind): Int = when (kind) {
    AccountKind.BANK -> R.string.account_bank
    AccountKind.CASH -> R.string.account_cash
    AccountKind.CREDIT_CARD -> R.string.account_card
}
@StringRes internal fun transactionLabel(kind: TransactionKind): Int = when (kind) {
    TransactionKind.EXPENSE -> R.string.type_expense
    TransactionKind.INCOME -> R.string.type_income
    TransactionKind.TRANSFER -> R.string.type_transfer
    TransactionKind.REFUND -> R.string.type_refund
    TransactionKind.ADJUSTMENT -> R.string.type_adjustment
}
