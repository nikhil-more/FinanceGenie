package com.financegenie.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.financegenie.android.data.LedgerRepository
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test

class FailureUiTest {
    private var failLoading = true
    @get:Rule(order = 0) val data = TestLedgerRule(decorate = { repository ->
        object : LedgerRepository by repository {
            override fun observe() = flow {
                if (failLoading) throw java.io.IOException("Synthetic storage read failure")
                emitAll(repository.observe())
            }
        }
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun loadFailureAllowsPrivacyAccessAndRetryRestartsObservation() {
        compose.waitTag("error_message")
        compose.onNodeWithText("Unable to load your ledger. Try again.").assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Privacy").assertIsDisplayed()
        compose.onNodeWithTag("tab_overview").performClick()
        failLoading = false
        compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
        compose.waitTag("add_transaction")
        compose.onNodeWithText(compose.activity.getString(R.string.overview_empty_title)).assertIsDisplayed()
    }
}
