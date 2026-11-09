package com.financegenie.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import com.financegenie.android.domain.MonthlyBudget
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.ui.LedgerViewModel
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

internal fun ComposeContentTestRule.budgetNode(tag: String) = onNodeWithTag("budget_list").performScrollToNode(hasTestTag(tag)).let {
    onNodeWithTag(tag).performScrollTo()
}

internal fun ComposeContentTestRule.addBudget(amount: String, category: String? = null, month: String? = null) {
    budgetNode("add_budget").performClick()
    if (category != null) choose("field_budget_scope", "category:$category")
    month?.let { field("field_month", it) }
    field("field_amount", amount)
    saveForm()
}

class BudgetUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedUiAccounts)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun remainingWarningsAndOverallCategoryLimitsUseLiveLedgerValues() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.addBudget("5000")
        compose.addBudget("5000", "food")
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.addEvent(TransactionKind.EXPENSE, "4000", "Synthetic Budget Food")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("budget_remaining_overall").assertTextEquals("₹1,000.00")
        compose.budgetNode("budget_warning_category:food").assertTextEquals("Approaching limit")
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.addEvent(TransactionKind.EXPENSE, "1500", "Synthetic Extra Food")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("budget_remaining_overall").assertTextEquals("₹500.00")
        compose.budgetNode("budget_warning_overall").assertTextEquals("Limit exceeded")
        compose.budgetNode("budget_remaining_category:food").assertTextEquals("₹500.00")
        compose.budgetNode("edit_budget_overall").performClick()
        compose.onNodeWithTag("field_month").assertIsNotEnabled()
        compose.field("field_amount", "10000")
        compose.saveForm()
        compose.budgetNode("budget_remaining_overall").assertTextEquals("₹4,500.00")
        compose.budgetNode("budget_warning_category:food").assertTextEquals("Limit exceeded")
        compose.activityRule.scenario.recreate()
        compose.budgetNode("budget_remaining_overall").assertTextEquals("₹4,500.00")
    }

    @Test fun zeroNoBudgetValidationDuplicateAndRapidSaveRemainDistinct() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("no_overall_budget").assertIsDisplayed()
        compose.budgetNode("add_budget").performClick()
        compose.field("field_amount", "-1")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.error_budget_amount)).assertIsDisplayed()
        compose.field("field_amount", "0")
        compose.field("field_month", "2026-13")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.error_budget_month)).assertIsDisplayed()
        compose.field("field_month", "2026-11")
        compose.runOnUiThread { model().saveEditor(); model().saveEditor() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("ledger_form")).fetchSemanticsNodes().isEmpty() }
        compose.dismissNotice()
        assertEquals(1, runBlocking { data.repository.read().budgets.size })
        compose.budgetNode("budget_limit_overall").assertTextEquals("₹0.00")
        compose.budgetNode("budget_warning_overall").assertTextEquals("At limit")
        compose.budgetNode("add_budget").performClick()
        compose.field("field_amount", "20")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.error_budget_exists)).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.budgetNode("remove_budget_overall").performClick()
        compose.onNodeWithTag("confirm_budget_remove").performClick()
        compose.waitTag("no_overall_budget")
        compose.dismissNotice()
        assertTrue(runBlocking { data.repository.read().budgets.isEmpty() })
    }

    @Test fun confirmedCopyPreservesExistingTargetAndKeepsMonthsIndependent() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.addBudget("5000")
        compose.addBudget("3000", "food")
        val source = runBlocking { data.repository.read().budgets }
        runBlocking { data.repository.saveBudget(MonthlyBudget(UUID(0, 800), YearMonth.of(2026, 12), 900_000, "food")) }
        compose.budgetNode("copy_budgets").performClick()
        compose.onNodeWithText("From 2026-11\nNew limits: 1\nExisting limits kept: 1\nArchived category limits skipped: 0").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("confirm_budget_copy").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("confirm_budget_copy")).fetchSemanticsNodes().isEmpty() }
        compose.dismissNotice()
        compose.budgetNode("selected_month").assertTextEquals("December 2026")
        compose.budgetNode("budget_limit_overall").assertTextEquals("₹5,000.00")
        compose.budgetNode("budget_limit_category:food").assertTextEquals("₹9,000.00")
        val copied = runBlocking { data.repository.read().budgets }
        source.forEach { assertEquals(it, copied.first { entry -> entry.value.id == it.value.id }) }
        compose.budgetNode("edit_budget_overall").performClick()
        compose.field("field_amount", "6000")
        compose.saveForm()
        compose.budgetNode("previous_month").performClick()
        compose.budgetNode("budget_limit_overall").assertTextEquals("₹5,000.00")
    }

    @Test fun staleCopyAndStaleEditShowActionableErrorsWithoutOverwriting() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.addBudget("5000")
        compose.budgetNode("copy_budgets").performClick()
        val original = runBlocking { data.repository.read().budgets.single() }
        runBlocking { data.repository.saveBudget(original.value.copy(limitMinor = 600_000), original.metadata.revision) }
        compose.onNodeWithTag("confirm_budget_copy").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.error_budget_changed)).assertIsDisplayed()
        assertEquals(1, runBlocking { data.repository.read().budgets.size })
        compose.onNodeWithText("Cancel").performClick()
        compose.budgetNode("edit_budget_overall").performClick()
        val latest = runBlocking { data.repository.read().budgets.single() }
        runBlocking { data.repository.saveBudget(latest.value.copy(limitMinor = 700_000), latest.metadata.revision) }
        compose.field("field_amount", "8000")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.error_stale)).assertIsDisplayed()
        assertEquals(700_000L, runBlocking { data.repository.read().budgets.single().value.limitMinor })
    }

    private fun model() = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
}
