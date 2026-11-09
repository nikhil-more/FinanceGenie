package com.financegenie.android

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.financegenie.android.privacy.PrivacyViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivacyUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(lockEnabled = true, seed = ::seedUiAccounts)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun coldGateCancellationBackgroundTimeoutAndRotationProtectContent() {
        compose.onNodeWithTag("locked_screen").assertIsDisplayed()
        compose.onNodeWithTag("tab_overview").assertDoesNotExist()
        compose.runOnUiThread { model().authenticationFinished(false) }
        compose.onNodeWithTag("locked_screen").assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").assertDoesNotExist()
        // Session-policy test seam only. Actual platform credential verification is a separate manual gate.
        compose.runOnUiThread { model().authenticationFinished(true) }
        compose.waitTag("add_transaction")
        compose.runOnUiThread { model().timeout(0) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_overview").assertIsDisplayed()
        compose.runOnUiThread { model().background(SystemClock.elapsedRealtime()) }
        compose.onNodeWithTag("locked_screen").assertIsDisplayed()
        compose.onNodeWithTag("add_transaction").assertDoesNotExist()
        compose.runOnUiThread { model().foreground(SystemClock.elapsedRealtime()) }
        compose.onNodeWithTag("locked_screen").assertIsDisplayed()
        compose.runOnUiThread { model().authenticationFinished(true) }
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("app_lock").performScrollTo().performClick()
        assertFalse(data.privacy.state.value.appLockEnabled)
    }

    @Test fun screenProtectionIsDefaultAndExplicitlyConfigurable() {
        assertTrue(secure())
        compose.runOnUiThread { model().authenticationFinished(true) }
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("screen_protection").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse("The screen-protection preference should change", data.privacy.state.value.protectScreen)
        compose.runOnUiThread { assertFalse("The authenticated session should remain visible", model().state.value.hidesContent) }
        compose.waitUntil(5_000) { !secure() }
        assertFalse(data.privacy.state.value.protectScreen)
        compose.runOnUiThread { model().lockNow() }
        compose.onNodeWithTag("locked_screen").assertIsDisplayed()
        compose.waitUntil(5_000) { secure() }
        assertTrue(secure())
    }

    private fun model() = ViewModelProvider(compose.activity)[PrivacyViewModel::class.java]
    private fun secure(): Boolean {
        var result = false
        compose.runOnUiThread { result = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        return result
    }
}
