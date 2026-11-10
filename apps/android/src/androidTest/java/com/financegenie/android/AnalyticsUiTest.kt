package com.financegenie.android

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.data.local.StorageGoldenFixture
import com.financegenie.android.data.local.storageExpense
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.domain.UNCATEGORIZED_ID
import java.time.LocalDate
import java.util.UUID
import org.junit.Rule
import org.junit.Test

internal fun ComposeContentTestRule.overviewNode(tag: String) = onNodeWithTag("overview_list").performScrollToNode(hasTestTag(tag)).let {
    onNodeWithTag(tag).performScrollTo()
}

internal fun ComposeContentTestRule.analyticsValue(tag: String, expected: String) {
    overviewNode(tag)
    val matches = if ('₹' in expected) hasMoneyText(expected) else hasText(expected)
    try {
        waitUntil(15_000) { onAllNodes(hasTestTag(tag) and matches).fetchSemanticsNodes().isNotEmpty() }
    } catch (error: Exception) {
        val actual = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text] }
        throw AssertionError("Expected $tag to show $expected; actual synthetic values: $actual", error)
    }
    onNodeWithTag(tag).assert(matches)
}

internal fun ComposeContentTestRule.openAnalyticsFilters() {
    overviewNode("toggle_filters")
    if (onAllNodes(hasTestTag("filter_account")).fetchSemanticsNodes().isEmpty()) overviewNode("toggle_filters").performClick()
}

private suspend fun seedAnalyticsGolden(repository: LedgerRepository) {
    val snapshot = StorageGoldenFixture.load().snapshot
    snapshot.accounts.forEach { repository.saveAccount(it) }
    snapshot.categories.filterNot { it.id == UNCATEGORIZED_ID }.forEach { repository.saveCategory(it) }
    repository.applyTransactions(snapshot.transactions.map { TransactionWrite(it,
        TransactionDetails("Synthetic ${it.kind} ${it.id.leastSignificantBits}", "Synthetic note 100%_literal")) })
    snapshot.budgets.forEach { repository.saveBudget(it) }
}

private fun fixtureEventId(label: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${label.toString().padStart(12, '0')}")

class AnalyticsUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedAnalyticsGolden)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun goldenSummaryCategoriesDailyMonthlyAndRecentActivityAgree() {
        compose.waitTag("add_transaction")
        compose.analyticsValue("metric_spending", "₹3,499.00")
        compose.analyticsValue("metric_expenses", "₹3,749.75")
        compose.analyticsValue("metric_refunds", "₹250.75")
        compose.analyticsValue("metric_income", "₹60,000.00")
        compose.analyticsValue("metric_surplus", "₹56,501.00")
        compose.analyticsValue("metric_tracked", "₹56,501.00")
        compose.analyticsValue("category_value_groceries", "₹2,000.00")
        compose.analyticsValue("category_value_shopping", "₹1,499.00")
        compose.overviewNode("toggle_daily_values").performClick()
        compose.analyticsValue("day_value_2026-11-02", "₹2,250.75")
        compose.analyticsValue("day_value_2026-11-03", "-₹250.75")
        compose.analyticsValue("month_value_2026-11", "₹3,499.00")
        compose.overviewNode("recent_${fixtureEventId(106)}").assertIsDisplayed()
        compose.overviewNode("view_transactions").performClick()
        compose.waitTag("transaction_list")
        compose.onNodeWithTag("transaction_${fixtureEventId(106)}").performScrollTo().assertIsDisplayed()
    }

    @Test fun accountCategoryAndLiteralSearchAreSharedWithTransactionsButNotBudgets() {
        compose.waitTag("add_transaction")
        compose.openAnalyticsFilters()
        compose.choose("filter_account", uiCard.toString())
        compose.analyticsValue("metric_spending", "₹1,499.00")
        compose.analyticsValue("metric_income", "₹0.00")
        compose.analyticsValue("metric_tracked", "₹56,501.00")
        compose.overviewNode("filter_account")
        compose.choose("filter_account", uiBank.toString())
        compose.choose("filter_category", "groceries")
        compose.analyticsValue("metric_spending", "₹2,000.00")
        compose.overviewNode("search_transactions")
        compose.field("search_transactions", "Synthetic EXPENSE")
        compose.analyticsValue("metric_spending", "₹2,250.75")
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.waitTag("transaction_list")
        compose.onNodeWithTag("transaction_list").performScrollToNode(hasTestTag("search_transactions"))
        compose.onNodeWithTag("search_transactions").assertTextEquals("Search merchant or notes", "Synthetic EXPENSE")
        compose.waitNoTag("transaction_${fixtureEventId(103)}")
        compose.onNodeWithTag("transaction_${fixtureEventId(102)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("budget_remaining_overall").assertMoneyEquals("₹1,501.00")
        compose.budgetNode("budget_remaining_category:groceries").assertMoneyEquals("₹3,000.00")
        compose.onNodeWithTag("tab_overview").performClick()
        compose.overviewNode("search_transactions")
        compose.field("search_transactions", "100%_literal")
        compose.analyticsValue("metric_spending", "₹2,000.00")
    }

    @Test fun editingRecentPurchaseUpdatesEveryAnalyticalSurfaceAndBudgets() {
        compose.waitTag("add_transaction")
        compose.overviewNode("edit_recent_${fixtureEventId(102)}").performClick()
        compose.field("field_amount", "2000")
        compose.saveForm()
        compose.analyticsValue("metric_spending", "₹3,248.25")
        compose.analyticsValue("category_value_groceries", "₹1,749.25")
        compose.overviewNode("toggle_daily_values").performClick()
        compose.analyticsValue("day_value_2026-11-02", "₹2,000.00")
        compose.analyticsValue("month_value_2026-11", "₹3,248.25")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("budget_remaining_overall").assertMoneyEquals("₹1,751.75")
        compose.budgetNode("budget_remaining_category:groceries").assertMoneyEquals("₹3,250.75")
    }

    @Test fun monthAndAllDateFiltersKeepHistoricalActivityAndCurrentPositionsDistinct() {
        compose.waitTag("add_transaction")
        compose.analyticsValue("metric_spending", "₹3,499.00")
        compose.overviewNode("next_month").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("selected_month") and hasText("December 2026")).fetchSemanticsNodes().isNotEmpty() }
        compose.analyticsValue("metric_spending", "₹0.00")
        compose.analyticsValue("metric_tracked", "₹56,501.00")
        compose.analyticsValue("month_value_2026-11", "₹3,499.00")
        compose.openAnalyticsFilters()
        compose.overviewNode("filter_all_dates").performClick()
        compose.analyticsValue("metric_spending", "₹3,499.00")
        compose.analyticsValue("metric_income", "₹60,000.00")
        compose.activityRule.scenario.recreate()
        compose.analyticsValue("metric_spending", "₹3,499.00")
    }
}

class AnalyticsScaleUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = { repository ->
        seedUiAccounts(repository)
        repository.applyTransactions((0 until 1_000).map { index ->
            TransactionWrite(storageExpense(index.toLong(), 1, LocalDate.of(2026, 11, index % 28 + 1)))
        })
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun thousandRowsRenderExactTotalsAndTextAlternatives() {
        compose.waitTag("add_transaction")
        compose.analyticsValue("metric_spending", "₹10.00")
        compose.analyticsValue("category_value_food", "₹10.00")
        compose.overviewNode("toggle_daily_values").performClick()
        compose.analyticsValue("day_value_2026-11-01", "₹0.36")
        compose.analyticsValue("month_value_2026-11", "₹10.00")
    }
}

class AnalyticsSingleUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = { repository ->
        seedUiAccounts(repository)
        repository.applyTransactions(listOf(TransactionWrite(storageExpense(amount = 1).copy(kind = TransactionKind.REFUND,
            unlinkedRefundConfirmed = true))))
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun singleNegativeRecordAndItsDeletionHaveUsableZeroStates() {
        compose.waitTag("add_transaction")
        compose.analyticsValue("metric_spending", "-₹0.01")
        compose.analyticsValue("category_value_food", "-₹0.01")
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.onNodeWithTag("delete_${UUID(0, 100)}").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitNoTag("transaction_${UUID(0, 100)}")
        compose.dismissNotice()
        compose.onNodeWithTag("tab_overview").performClick()
        compose.analyticsValue("metric_spending", "₹0.00")
        compose.onNodeWithTag("overview_list").performScrollToNode(hasText("No expenses or refunds match these filters."))
        compose.onNodeWithText("No expenses or refunds match these filters.").assertIsDisplayed()
        compose.analyticsValue("month_value_2026-11", "₹0.00")
    }
}
