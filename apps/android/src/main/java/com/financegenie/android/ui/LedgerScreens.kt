package com.financegenie.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.financegenie.android.R
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.data.TransactionSource
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.domain.UNCATEGORIZED_ID
import com.financegenie.android.privacy.PrivacyUiState
import com.financegenie.android.privacy.PrivacyViewModel
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun money(minor: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(BigDecimal.valueOf(minor, 2))
private fun magnitudeMoney(minor: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(BigDecimal.valueOf(minor, 2).abs())

@Composable internal fun ErrorText(@StringRes message: Int) {
    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("error_message").semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable internal fun LedgerContent(state: LedgerUiState, retry: () -> Unit, content: @Composable () -> Unit) {
    when {
        state.loading -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.loading))
        }
        state.error != null -> Column(Modifier.padding(24.dp)) {
            ErrorText(state.error)
            Button(onClick = retry) { Text(stringResource(R.string.retry)) }
        }
        else -> content()
    }
}

@Composable internal fun MonthSelector(state: LedgerUiState, model: LedgerViewModel) {
    val previous = stringResource(R.string.previous_month)
    val next = stringResource(R.string.next_month)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { model.moveMonth(-1) }, modifier = Modifier.testTag("previous_month").semantics { contentDescription = previous }) {
            Text("‹", style = MaterialTheme.typography.headlineSmall) }
        Text(state.filters.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp).testTag("selected_month"))
        TextButton(onClick = { model.moveMonth(1) }, modifier = Modifier.testTag("next_month").semantics { contentDescription = next }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable internal fun OverviewScreen(state: LedgerUiState, model: LedgerViewModel, accounts: () -> Unit) {
    val ledger = requireNotNull(state.ledger)
    val report = requireNotNull(state.report)
    LazyColumn(modifier = Modifier.testTag("overview_list"), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MonthSelector(state, model) }
        if (ledger.accounts.isEmpty()) item {
            Text(stringResource(R.string.overview_empty_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.no_accounts_help))
            Button(onClick = accounts, modifier = Modifier.testTag("overview_accounts")) { Text(stringResource(R.string.add_account)) }
        } else {
            item { Metric(R.string.net_spending, report.netSpendingMinor, "metric_spending") }
            item { Text(stringResource(R.string.spending_help)) }
            item { Metric(R.string.gross_spending, report.grossSpendingMinor, "metric_expenses") }
            item { Metric(R.string.refunds, report.refundsMinor, "metric_refunds") }
            item { Metric(R.string.income, report.incomeMinor, "metric_income") }
            item { Metric(R.string.recorded_surplus, report.recordedSurplusMinor, "metric_surplus") }
            item { Metric(R.string.estimated_tracked_balance, requireNotNull(state.currentReport).totalTrackedPositionMinor, "metric_tracked") }
            item { Text(stringResource(R.string.estimated_as_of, state.currentReport!!.balancesAsOfDate.toString())) }
            item { Button(onClick = accounts) { Text(stringResource(R.string.manage_accounts)) } }
        }
    }
}

@Composable private fun Metric(@StringRes label: Int, amount: Long, tag: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            Text(money(amount), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(tag))
        }
    }
}

@Composable internal fun TransactionsScreen(state: LedgerUiState, model: LedgerViewModel) {
    val ledger = requireNotNull(state.ledger)
    LazyColumn(modifier = Modifier.testTag("transaction_list"), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthSelector(state, model)
                FormField(state.filters.search, { query -> model.filter { it.copy(search = query) } }, R.string.search_transactions, "search_transactions")
                ChoiceField(stringResource(R.string.account), state.filters.accountId?.toString() ?: "all",
                    listOf(Choice("all", stringResource(R.string.all_accounts))) + ledger.accounts.map { Choice(it.value.id.toString(), it.value.name) }, "filter_account") { id ->
                    model.filter { it.copy(accountId = id.takeUnless { it == "all" }?.let(java.util.UUID::fromString)) }
                }
                ChoiceField(stringResource(R.string.category), state.filters.categoryId ?: "all",
                    listOf(Choice("all", stringResource(R.string.all_categories))) + ledger.categories.map { Choice(it.value.id, it.value.name) }, "filter_category") { id ->
                    model.filter { it.copy(categoryId = id.takeUnless { it == "all" }) }
                }
                ToggleRow(R.string.all_dates, state.filters.allDates, "filter_all_dates") { enabled -> model.filter { it.copy(allDates = enabled) } }
                ToggleRow(R.string.show_deleted, state.filters.showDeleted, "filter_deleted") { enabled -> model.filter { it.copy(showDeleted = enabled) } }
            }
        }
        if (state.rows.isEmpty()) item {
            Text(stringResource(if (ledger.transactions.isEmpty()) R.string.transactions_empty_title else R.string.no_matching_transactions),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.no_matching_help))
        }
        items(state.rows, key = { it.transaction.id }) { record -> TransactionCard(record, state, model) }
    }
}

@Composable private fun TransactionCard(record: TransactionRecord, state: LedgerUiState, model: LedgerViewModel) {
    val event = record.transaction
    val ledger = requireNotNull(state.ledger)
    val account = ledger.accounts.first { it.value.id == event.accountId }.value.name
    val destination = event.destinationAccountId?.let { id -> ledger.accounts.first { it.value.id == id }.value.name }
    val category = event.categoryId?.let { id -> ledger.categories.first { it.value.id == id }.value.name }
    Card(Modifier.fillMaxWidth().testTag("transaction_${event.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(transactionLabel(event.kind)), style = MaterialTheme.typography.labelLarge)
            Text(money(event.amountMinor), style = MaterialTheme.typography.titleLarge)
            if (record.details.merchant.isNotBlank()) Text(record.details.merchant, style = MaterialTheme.typography.titleMedium)
            Text("${event.effectiveDate} · $account${destination?.let { " → $it" }.orEmpty()}")
            category?.let { Text(it) }
            Text(stringResource(if (record.details.source == TransactionSource.MANUAL) R.string.source_manual else R.string.source_csv), style = MaterialTheme.typography.labelMedium)
            if (record.details.source == TransactionSource.MANUAL && ledger.evidence.any { it.isActive && it.input.transactionId == event.id && it.input.source == TransactionSource.CSV }) {
                Text(stringResource(R.string.source_csv_attached), style = MaterialTheme.typography.labelMedium)
            }
            if (record.details.notes.isNotBlank()) Text(record.details.notes)
            event.adjustmentReason?.let { Text(it) }
            if (event.isDeleted) {
                Text(stringResource(R.string.deleted))
                TextButton(onClick = { model.restore(record) }, modifier = Modifier.testTag("restore_${event.id}")) { Text(stringResource(R.string.restore)) }
            } else {
                Row {
                    TextButton(onClick = { model.editTransaction(record) }, modifier = Modifier.testTag("edit_${event.id}")) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = { model.askDelete(record) }, modifier = Modifier.testTag("delete_${event.id}")) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

@Composable internal fun AccountsScreen(state: LedgerUiState, model: LedgerViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.accounts_help))
            Button(onClick = model::newAccount, modifier = Modifier.testTag("add_account")) { Text(stringResource(R.string.add_account)) }
        }
        if (state.ledger!!.accounts.isEmpty()) item { Text(stringResource(R.string.no_accounts)) }
        items(state.ledger.accounts, key = { it.value.id }) { record ->
            val account = record.value
            val balance = state.currentReport!!.accountPositionsMinor.getValue(account.id)
            Card(Modifier.fillMaxWidth().testTag("account_${account.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(accountLabel(account.kind)))
                    if (account.isArchived) Text(stringResource(R.string.archived))
                    Text(stringResource(if (account.kind == AccountKind.CREDIT_CARD) {
                        if (balance < 0) R.string.amount_owed else R.string.credit_balance
                    } else R.string.estimated_balance))
                    Text(if (account.kind == AccountKind.CREDIT_CARD) magnitudeMoney(balance) else money(balance), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("balance_${account.id}"))
                    Text(stringResource(R.string.estimated_as_of, state.currentReport.balancesAsOfDate.toString()))
                    state.observations[account.id]?.let {
                        Text(stringResource(R.string.user_reported_as_of, it.observation.asOfDate.toString(), money(it.observation.reportedBalanceMinor)))
                        Text(stringResource(R.string.balance_difference, money(it.differenceMinor)))
                    }
                    Row {
                        TextButton(onClick = { model.editAccount(record) }, modifier = Modifier.testTag("edit_account_${account.id}")) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = { model.archive(record) }, modifier = Modifier.testTag("archive_account_${account.id}")) {
                            Text(stringResource(if (account.isArchived) R.string.unarchive else R.string.archive))
                        }
                    }
                    TextButton(onClick = { model.observeBalance(account) }, modifier = Modifier.testTag("observe_${account.id}")) { Text(stringResource(R.string.record_balance)) }
                }
            }
        }
    }
}

@Composable internal fun CategoriesScreen(state: LedgerUiState, model: LedgerViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.categories_help))
            Button(onClick = model::newCategory, modifier = Modifier.testTag("add_category")) { Text(stringResource(R.string.add_category)) }
        }
        items(state.ledger!!.categories, key = { it.value.id }) { record ->
            Card(Modifier.fillMaxWidth().testTag("category_${record.value.id}")) {
                Column(Modifier.padding(16.dp)) {
                    Text(record.value.name, style = MaterialTheme.typography.titleLarge)
                    if (record.value.isArchived) Text(stringResource(R.string.archived))
                    Row {
                        TextButton(onClick = { model.editCategory(record) }, modifier = Modifier.testTag("edit_category_${record.value.id}")) { Text(stringResource(R.string.edit)) }
                        if (record.value.id != UNCATEGORIZED_ID) TextButton(onClick = { model.archiveCategory(record) }, modifier = Modifier.testTag("archive_category_${record.value.id}")) {
                            Text(stringResource(if (record.value.isArchived) R.string.unarchive else R.string.archive))
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun ToggleRow(@StringRes label: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), modifier = Modifier.weight(1f).padding(top = 10.dp))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable internal fun SettingsScreen(state: PrivacyUiState, model: PrivacyViewModel, authenticate: (Boolean) -> Unit, accounts: () -> Unit, categories: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = accounts, modifier = Modifier.fillMaxWidth().testTag("manage_accounts")) { Text(stringResource(R.string.manage_accounts)) }
        Button(onClick = categories, modifier = Modifier.fillMaxWidth().testTag("manage_categories")) { Text(stringResource(R.string.manage_categories)) }
        HorizontalDivider()
        Text(stringResource(R.string.privacy), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        ToggleRow(R.string.app_lock, state.settings.appLockEnabled, "app_lock") { if (it) authenticate(true) else model.disableLock() }
        Text(stringResource(R.string.app_lock_help))
        if (state.settings.appLockEnabled) {
            ChoiceField(stringResource(R.string.lock_timeout), state.settings.timeoutSeconds.toString(), listOf(
                Choice("0", stringResource(R.string.timeout_immediate)), Choice("30", stringResource(R.string.timeout_30_seconds)),
                Choice("60", stringResource(R.string.timeout_1_minute)), Choice("300", stringResource(R.string.timeout_5_minutes)),
            ), "lock_timeout") { model.timeout(it.toInt()) }
            Button(onClick = model::lockNow) { Text(stringResource(R.string.lock_now)) }
        }
        ToggleRow(R.string.screen_protection, state.settings.protectScreen, "screen_protection", model::protectScreen)
        Text(stringResource(R.string.screen_protection_help))
        state.error?.let { ErrorText(it) }
        Text(stringResource(R.string.local_only_help), style = MaterialTheme.typography.bodyMedium)
    }
}
