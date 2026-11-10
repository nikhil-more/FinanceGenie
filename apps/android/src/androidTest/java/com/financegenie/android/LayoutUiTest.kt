package com.financegenie.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.privacy.PrivacyViewModel
import com.financegenie.android.ui.FinanceGenieApp
import com.financegenie.android.ui.LedgerViewModel
import com.financegenie.android.ui.theme.FinanceGenieTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test

class LayoutUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = { repository ->
        seedUiAccounts(repository)
        val expense = com.financegenie.android.data.local.storageExpense(amount = 10_000)
        val refund = expense.copy(id = java.util.UUID(0, 101), kind = TransactionKind.REFUND, categoryId = "shopping", unlinkedRefundConfirmed = true)
        repository.applyTransactions(listOf(expense, refund).map { com.financegenie.android.data.TransactionWrite(it) })
        repository.saveCategory(com.financegenie.android.domain.LedgerCategory("food", "Long synthetic category name for large-font accessibility and wrapping", true), 1)
    })
    @get:Rule(order = 1) val compose = createComposeRule()
    private val store = ViewModelStore()

    @After fun clearModels() { compose.runOnUiThread { store.clear() } }

    @Test fun twoHundredPercentFontDarkAndLightModesKeepNavigationAndFormsUsable() {
        lateinit var ledger: LedgerViewModel
        lateinit var privacy: PrivacyViewModel
        var largeDark by mutableStateOf(true)
        compose.runOnUiThread {
            val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                    LedgerViewModel::class.java -> LedgerViewModel(data.repository, uiClock)
                    PrivacyViewModel::class.java -> PrivacyViewModel(data.privacy)
                    else -> error("Unexpected test model")
                } as T
            })
            ledger = provider[LedgerViewModel::class.java]
            privacy = provider[PrivacyViewModel::class.java]
        }
        compose.setContent {
            val density = LocalDensity.current
            val privacyState by privacy.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeDark) 2f else 1f)) {
                FinanceGenieTheme(darkTheme = largeDark) {
                    FinanceGenieApp(ledger, privacyState, privacy, authenticate = {})
                }
            }
        }
        compose.waitTag("add_transaction")
        listOf("transactions", "budgets", "settings", "overview").forEach {
            compose.onNodeWithTag("tab_$it").performClick().assertIsDisplayed()
        }
        compose.analyticsValue("category_value_shopping", "-₹100.00")
        capture("step6-font200-dark-analytics.png", "overview_list")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.addBudget("5000")
        compose.budgetNode("budget_remaining_overall").assertMoneyEquals("₹5,000.00")
        capture("step5-font200-dark-budget.png", "budget_list")
        compose.onNodeWithTag("tab_overview").performClick()
        compose.onNodeWithTag("add_transaction").performClick()
        compose.choose("field_kind", TransactionKind.ADJUSTMENT.name)
        compose.field("field_amount", "1.25")
        compose.choose("field_account", uiCash.toString())
        compose.field("field_reason", "Synthetic accessibility correction")
        capture("step4-font200-dark.png", "ledger_form")
        compose.saveForm()
        compose.runOnIdle { largeDark = false }
        capture("step4-font100-light.png", "overview_list")
    }

    private fun capture(name: String, tag: String) {
        compose.onNodeWithTag(tag).saveSyntheticCapture(name)
    }
}
