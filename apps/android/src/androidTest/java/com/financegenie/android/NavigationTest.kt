package com.financegenie.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun topLevelDestinationsAreReachable() {
        compose.onNodeWithText(compose.activity.getString(R.string.overview_empty_title))
            .assertIsDisplayed()

        val destinations = listOf(
            "transactions" to R.string.transactions_empty_title,
            "budgets" to R.string.budgets_empty_title,
            "settings" to R.string.settings_empty_title,
            "overview" to R.string.overview_empty_title,
        )
        destinations.forEach { (route, title) ->
            compose.onNodeWithTag("tab_$route").performClick().assertIsSelected()
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
