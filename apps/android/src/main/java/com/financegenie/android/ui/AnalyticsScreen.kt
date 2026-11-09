package com.financegenie.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.financegenie.android.R
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.domain.DailySpending
import com.financegenie.android.domain.LedgerAnalytics
import java.math.BigDecimal
import java.math.MathContext
import java.util.UUID
import kotlin.math.abs

@Composable internal fun ActivityFilters(state: LedgerUiState, model: LedgerViewModel, expandable: Boolean, feedOptions: Boolean = false) {
    val ledger = requireNotNull(state.ledger)
    var expanded by rememberSaveable { mutableStateOf(!expandable) }
    val accountName = ledger.accounts.firstOrNull { it.value.id == state.filters.accountId }?.value?.name ?: stringResource(R.string.all_accounts)
    val categoryName = ledger.categories.firstOrNull { it.value.id == state.filters.categoryId }?.value?.name ?: stringResource(R.string.all_categories)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.filters.allDates) Text(stringResource(R.string.all_dates), style = MaterialTheme.typography.titleMedium)
        else MonthSelector(state, model)
        if (expandable) {
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("toggle_filters")) { Text(stringResource(R.string.activity_filters)) }
            Text(stringResource(R.string.filter_summary, accountName, categoryName))
            if (state.filters.search.isNotBlank()) Text(stringResource(R.string.filter_search_summary, state.filters.search))
            val count = state.rows.count { !it.transaction.isDeleted }
            Text(pluralStringResource(R.plurals.activity_count, count, count), modifier = Modifier.testTag("activity_count"))
        }
        if (expanded) {
            FormField(state.filters.search, { query -> model.filter { it.copy(search = query) } }, R.string.search_transactions, "search_transactions")
            ChoiceField(stringResource(R.string.account), state.filters.accountId?.toString() ?: "all",
                listOf(Choice("all", stringResource(R.string.all_accounts))) + ledger.accounts.map { Choice(it.value.id.toString(), it.value.name) }, "filter_account") { id ->
                model.filter { it.copy(accountId = id.takeUnless { it == "all" }?.let(UUID::fromString)) }
            }
            ChoiceField(stringResource(R.string.category), state.filters.categoryId ?: "all",
                listOf(Choice("all", stringResource(R.string.all_categories))) + ledger.categories.map { Choice(it.value.id, it.value.name) }, "filter_category") { id ->
                model.filter { it.copy(categoryId = id.takeUnless { it == "all" }) }
            }
            ToggleRow(R.string.all_dates, state.filters.allDates, "filter_all_dates") { enabled -> model.filter { it.copy(allDates = enabled) } }
            if (feedOptions) ToggleRow(R.string.show_deleted, state.filters.showDeleted, "filter_deleted") { enabled -> model.filter { it.copy(showDeleted = enabled) } }
            Text(stringResource(R.string.activity_filter_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun OverviewScreen(state: LedgerUiState, model: LedgerViewModel, accounts: () -> Unit, transactions: () -> Unit) {
    val ledger = requireNotNull(state.ledger)
    val analytics = requireNotNull(state.analytics)
    val report = analytics.report
    val categoryScale = analytics.categories.maxOfOrNull { magnitude(it.netSpendingMinor) } ?: BigDecimal.ONE
    val monthlyScale = analytics.months.maxOfOrNull { magnitude(it.netSpendingMinor) } ?: BigDecimal.ONE
    val recent = state.rows.asSequence().filterNot { it.transaction.isDeleted }.take(5).toList()
    LazyColumn(modifier = Modifier.testTag("overview_list"), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ActivityFilters(state, model, expandable = true) }
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
            item { Metric(R.string.estimated_tracked_balance, state.currentReport!!.totalTrackedPositionMinor, "metric_tracked") }
            item {
                Text(stringResource(R.string.estimated_as_of, state.currentReport!!.balancesAsOfDate.toString()))
                Text(stringResource(R.string.current_balance_scope), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = accounts) { Text(stringResource(R.string.manage_accounts)) }
            }
            item { SectionTitle(R.string.category_spending); Text(stringResource(R.string.category_chart_help)) }
            if (analytics.categories.isEmpty()) item { Text(stringResource(R.string.no_spending_activity)) }
            items(analytics.categories, key = { "category_${it.category.id}" }) { point ->
                Card(Modifier.fillMaxWidth().testTag("category_${point.category.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (point.category.isArchived) stringResource(R.string.archived_name, point.category.name) else point.category.name)
                        Text(money(point.netSpendingMinor), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("category_value_${point.category.id}"))
                        SignedBar(point.netSpendingMinor, categoryScale)
                    }
                }
            }
            item { DailyTrend(analytics) }
            item {
                SectionTitle(R.string.monthly_spending)
                Text(stringResource(R.string.trend_range, analytics.months.first().month.toString(), analytics.months.last().month.toString()))
            }
            items(analytics.months, key = { "month_${it.month}" }) { point ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(point.month.toString())
                        Text(money(point.netSpendingMinor), modifier = Modifier.testTag("month_value_${point.month}"))
                        SignedBar(point.netSpendingMinor, monthlyScale)
                    }
                }
            }
            item { SectionTitle(R.string.recent_activity) }
            if (recent.isEmpty()) item { Text(stringResource(R.string.no_recent_activity)) }
            items(recent, key = { "recent_${it.transaction.id}" }) { record -> RecentTransaction(record, state, model) }
            item { TextButton(onClick = transactions, modifier = Modifier.fillMaxWidth().testTag("view_transactions")) { Text(stringResource(R.string.view_transactions)) } }
        }
    }
}

@Composable private fun SectionTitle(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

private fun magnitude(value: Long): BigDecimal = BigDecimal.valueOf(value).abs()
// Floating-point fractions are used only for pixels; every displayed amount remains integer paise.
private fun fraction(value: Long, scale: BigDecimal): Float = if (scale.signum() == 0) 0f else
    BigDecimal.valueOf(value).divide(scale, MathContext.DECIMAL64).toFloat().coerceIn(-1f, 1f)

@Composable private fun SignedBar(value: Long, scale: BigDecimal) {
    val ratio = abs(fraction(value, scale))
    val background = MaterialTheme.colorScheme.surfaceVariant
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.tertiary
    Row(Modifier.fillMaxWidth().height(14.dp).clearAndSetSemantics { }) {
        Box(Modifier.weight(1f).fillMaxHeight().background(background), contentAlignment = Alignment.CenterEnd) {
            if (value < 0) Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).background(negative))
        }
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurfaceVariant))
        Box(Modifier.weight(1f).fillMaxHeight().background(background), contentAlignment = Alignment.CenterStart) {
            if (value > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).background(positive))
        }
    }
}

@Composable private fun DailyTrend(analytics: LedgerAnalytics) {
    var showValues by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(R.string.daily_spending)
            Text(stringResource(R.string.trend_range, analytics.dailyRange.startInclusive.toString(), analytics.dailyRange.endExclusive.minusDays(1).toString()), modifier = Modifier.testTag("daily_range"))
            Text(stringResource(R.string.daily_chart_help))
            DailyBars(analytics.days)
            TextButton(onClick = { showValues = !showValues }, modifier = Modifier.fillMaxWidth().testTag("toggle_daily_values")) {
                Text(stringResource(if (showValues) R.string.hide_daily_values else R.string.show_daily_values))
            }
            if (showValues) analytics.days.forEach { day ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(day.date.toString())
                    Text(money(day.netSpendingMinor), modifier = Modifier.testTag("day_value_${day.date}"))
                }
            }
        }
    }
}

@Composable private fun DailyBars(points: List<DailySpending>) {
    val scale = points.maxOfOrNull { magnitude(it.netSpendingMinor) } ?: BigDecimal.ONE
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.tertiary
    val zero = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics { }) {
        val center = size.height / 2
        drawLine(zero, Offset(0f, center), Offset(size.width, center), 2f)
        val slot = size.width / points.size.coerceAtLeast(1)
        points.forEachIndexed { index, point ->
            val y = center - fraction(point.netSpendingMinor, scale) * (center - 4.dp.toPx())
            drawRect(if (point.netSpendingMinor < 0) negative else positive,
                Offset(slot * (index + 0.2f), minOf(center, y)), Size(slot * 0.6f, abs(center - y)))
        }
    }
}

@Composable private fun RecentTransaction(record: TransactionRecord, state: LedgerUiState, model: LedgerViewModel) {
    val event = record.transaction
    val account = state.ledger!!.accounts.first { it.value.id == event.accountId }.value.name
    val title = record.details.merchant.ifBlank { stringResource(transactionLabel(event.kind)) }
    val edit = stringResource(R.string.recent_edit, title, event.effectiveDate.toString())
    Card(Modifier.fillMaxWidth().testTag("recent_${event.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(transactionLabel(event.kind)))
            Text(money(event.amountMinor))
            Text("${event.effectiveDate} · $account")
            Text(stringResource(if (record.details.source == com.financegenie.android.data.TransactionSource.MANUAL) R.string.source_manual else R.string.source_csv))
            TextButton(onClick = { model.editTransaction(record) }, modifier = Modifier.testTag("edit_recent_${event.id}").semantics { contentDescription = edit }) { Text(stringResource(R.string.edit)) }
        }
    }
}
