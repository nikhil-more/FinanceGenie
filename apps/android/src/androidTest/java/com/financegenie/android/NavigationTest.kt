package com.financegenie.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule(order = 0)
    val data = TestLedgerRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun topLevelDestinationsAreReachable() {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("add_transaction")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.overview_empty_title))
            .assertIsDisplayed()

        val destinations = listOf(
            "transactions" to R.string.transactions_empty_title,
            "budgets" to R.string.budgets_empty_title,
            "settings" to R.string.manage_accounts,
            "overview" to R.string.overview_empty_title,
        )
        destinations.forEach { (route, title) ->
            compose.onNodeWithTag("tab_$route").performClick().assertIsSelected()
            if (route == "transactions") compose.onNodeWithTag("transaction_list").performScrollToNode(hasText(compose.activity.getString(title)))
            compose.onNodeWithText(compose.activity.getString(title)).assertIsDisplayed()
        }
    }

    @Test
    fun selectedDestinationSurvivesActivityRecreation() {
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_budgets").assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.budgets_empty_title))
            .assertIsDisplayed()
    }
}
