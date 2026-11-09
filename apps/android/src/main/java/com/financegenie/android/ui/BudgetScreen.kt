package com.financegenie.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.financegenie.android.R
import com.financegenie.android.data.StoredLedger
import com.financegenie.android.data.StoredRecord
import com.financegenie.android.domain.BudgetWarning
import com.financegenie.android.domain.MonthlyBudget
import java.time.YearMonth

internal fun budgetScopeKey(categoryId: String?): String = categoryId?.let { "category:$it" } ?: "overall"

@Composable internal fun budgetScopeLabel(categoryId: String?, ledger: StoredLedger): String {
    if (categoryId == null) return stringResource(R.string.budget_overall)
    val category = ledger.categories.first { it.value.id == categoryId }.value
    return if (category.isArchived) stringResource(R.string.archived_name, category.name) else category.name
}

@Composable internal fun BudgetsScreen(state: LedgerUiState, model: LedgerViewModel, busy: Boolean) {
    val ledger = requireNotNull(state.ledger)
    LazyColumn(modifier = Modifier.testTag("budget_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MonthSelector(state, model) }
        if (state.budgets.isEmpty()) item {
            Text(stringResource(R.string.budgets_empty_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.budget_help))
                Text(stringResource(R.string.budget_filter_scope), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { model.newBudget() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("add_budget")) { Text(stringResource(R.string.add_budget)) }
                TextButton(onClick = model::askCopyBudgets, enabled = !busy && state.budgets.isNotEmpty() && state.filters.month < YearMonth.of(9999, 12),
                    modifier = Modifier.fillMaxWidth().testTag("copy_budgets")) { Text(stringResource(R.string.copy_budgets)) }
            }
        }
        if (state.budgets.none { it.record.value.categoryId == null }) item {
            Card(Modifier.fillMaxWidth().testTag("no_overall_budget")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.budget_no_overall), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.budget_no_overall_help))
                    TextButton(onClick = { model.newBudget() }, enabled = !busy) { Text(stringResource(R.string.budget_set_overall)) }
                }
            }
        }
        items(state.budgets, key = { it.record.value.id }) { row -> BudgetCard(row, ledger, model, busy) }
        item { Text(stringResource(R.string.budget_warning_help), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun BudgetCard(row: BudgetRow, ledger: StoredLedger, model: LedgerViewModel, busy: Boolean) {
    val budget = row.record.value
    val progress = row.progress
    val scope = budgetScopeKey(budget.categoryId)
    val label = budgetScopeLabel(budget.categoryId, ledger)
    val editLabel = stringResource(R.string.budget_edit_action, label)
    val removeLabel = stringResource(R.string.budget_remove_action, label)
    Card(Modifier.fillMaxWidth().testTag("budget_$scope")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            BudgetValue(R.string.budget_limit, budget.limitMinor, "budget_limit_$scope")
            BudgetValue(R.string.gross_spending, progress.grossSpendingMinor, "budget_expenses_$scope")
            BudgetValue(R.string.refunds, progress.refundsMinor, "budget_refunds_$scope")
            BudgetValue(R.string.net_spending, progress.netSpendingMinor, "budget_spending_$scope")
            Text(stringResource(if (progress.isOverspent) R.string.budget_over_by else R.string.budget_remaining), style = MaterialTheme.typography.labelLarge)
            Text(money(if (progress.isOverspent) -progress.remainingMinor else progress.remainingMinor), style = MaterialTheme.typography.headlineSmall,
                color = if (progress.isOverspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("budget_remaining_$scope").semantics { liveRegion = LiveRegionMode.Polite })
            val warning = when (progress.warning) {
                BudgetWarning.NONE -> null
                BudgetWarning.NEAR_LIMIT -> R.string.budget_near_limit
                BudgetWarning.AT_LIMIT -> R.string.budget_at_limit
                BudgetWarning.OVER_LIMIT -> R.string.budget_over_limit
            }
            warning?.let { Text(stringResource(it), style = MaterialTheme.typography.titleMedium,
                color = if (progress.isOverspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("budget_warning_$scope")) }
            TextButton(onClick = { model.editBudget(row.record) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("edit_budget_$scope").semantics { contentDescription = editLabel }) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = { model.askRemoveBudget(row.record) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("remove_budget_$scope").semantics { contentDescription = removeLabel }) { Text(stringResource(R.string.remove_budget)) }
        }
    }
}

@Composable private fun BudgetValue(label: Int, minor: Long, tag: String) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Text(money(minor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
}

@Composable internal fun BudgetConfirmations(copy: BudgetCopyConfirmation?, removal: StoredRecord<MonthlyBudget>?, ledger: StoredLedger?,
    error: Int?, busy: Boolean, model: LedgerViewModel) {
    if (copy != null) AlertDialog(onDismissRequest = model::cancelBudgetCopy,
        title = { Text(stringResource(R.string.copy_budget_title, copy.plan.toMonth.toString())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.copy_budget_preview, copy.plan.fromMonth.toString(), copy.plan.toCopy.size, copy.plan.existingCount, copy.plan.archivedCount))
                Text(stringResource(R.string.copy_budget_help))
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = { TextButton(onClick = model::confirmBudgetCopy, enabled = !busy && copy.plan.toCopy.isNotEmpty(), modifier = Modifier.testTag("confirm_budget_copy")) {
            Text(stringResource(R.string.copy_budget_confirm))
        } },
        dismissButton = { TextButton(onClick = model::cancelBudgetCopy, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    if (removal != null && ledger != null) AlertDialog(onDismissRequest = model::cancelBudgetRemoval,
        title = { Text(stringResource(R.string.remove_budget_title, budgetScopeLabel(removal.value.categoryId, ledger), removal.value.month.toString())) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.remove_budget_help))
            error?.let { ErrorText(it) }
        } },
        confirmButton = { TextButton(onClick = model::confirmBudgetRemoval, enabled = !busy, modifier = Modifier.testTag("confirm_budget_remove")) { Text(stringResource(R.string.remove_budget)) } },
        dismissButton = { TextButton(onClick = model::cancelBudgetRemoval, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
