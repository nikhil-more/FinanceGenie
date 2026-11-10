package com.financegenie.android

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.privacy.PrivacyViewModel
import com.financegenie.android.ui.FinanceGenieApp
import com.financegenie.android.ui.LedgerViewModel
import com.financegenie.android.ui.theme.FinanceGenieTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

class CsvFontUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedUiAccounts)
    @get:Rule(order = 1) val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun clear() { compose.runOnUiThread { store.clear() } }
    @Test fun largeFontDarkPreviewAndClassificationControlsRemainReachable() {
        lateinit var ledger: LedgerViewModel
        lateinit var privacy: PrivacyViewModel
        compose.runOnUiThread {
            val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                    LedgerViewModel::class.java -> LedgerViewModel(data.repository, uiClock)
                    PrivacyViewModel::class.java -> PrivacyViewModel(data.privacy)
                    else -> error("Unexpected test ViewModel")
                } as T
            })
            ledger = provider[LedgerViewModel::class.java]
            privacy = provider[PrivacyViewModel::class.java]
        }
        compose.setContent {
            val privacyState by privacy.state.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                FinanceGenieTheme(darkTheme = true) { FinanceGenieApp(ledger, privacyState, privacy, authenticate = {}) }
            }
        }
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("manage_imports").performScrollTo().performClick()
        compose.runOnUiThread {
            ledger.csv.read { ("date,amount,direction,merchant,category,reference\n" +
                "2026-11-02,1.25,DEBIT,Long synthetic merchant label for accessibility and wrapping,Food,SYN-FONT\n" +
                "2026-11-02,2.00,CREDIT,Synthetic credit,Food,SYN-FONT-C\n").byteInputStream() }
        }
        compose.waitUntil(15_000) { ledger.csv.state.value.document != null }
        compose.onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_account"))
        compose.choose("csv_account", uiBank.toString())
        compose.onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_preview"))
        compose.onNodeWithTag("csv_preview").performClick()
        compose.waitUntil(15_000) { ledger.csv.state.value.plan != null }
        compose.onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_count_review"))
        compose.onNodeWithTag("csv_count_review").assertTextEquals("Needs review: 2")
        val image = compose.onNodeWithTag("csv_screen").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "step7-font200-dark-csv.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_review_3"))
        compose.onNodeWithTag("csv_review_3").performClick()
        compose.choose("csv_kind", "REFUND")
        compose.onNodeWithTag("csv_confirm_unlinked").performScrollTo().performClick()
        compose.onNodeWithTag("csv_accept_row").performClick()
        compose.waitUntil(15_000) { ledger.csv.state.value.plan?.acceptedCount == 1 }
        compose.onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_commit"))
        compose.onNodeWithTag("csv_commit").assertIsDisplayed().assertIsEnabled()
    }
}
