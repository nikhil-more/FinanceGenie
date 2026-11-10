package com.financegenie.android

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.RoomLedgerRepository
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.privacy.PrivacyPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiRestartTest {
    private lateinit var database: FinanceGenieDatabase
    private lateinit var repository: LedgerRepository
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FinanceGenieApplication
    private val markers get() = app.getSharedPreferences("ui-restart-probe-only", Context.MODE_PRIVATE)

    @get:Rule(order = 0) val isolated = object : TestWatcher() {
        override fun starting(description: Description) {
            if (description.methodName.startsWith("a_")) {
                app.deleteDatabase("ui-restart-probe-only.db")
                check(markers.edit().clear().commit())
            }
            database = FinanceGenieDatabase.open(app, "ui-restart-probe-only.db", uiClock)
            repository = RoomLedgerRepository(database, uiClock)
            if (description.methodName.startsWith("a_")) runBlocking { seedUiAccounts(repository) }
            app.replaceTestContainer(object : AppContainer {
                override val repository = this@UiRestartTest.repository
                override val clock = uiClock
                override val privacy = PrivacyPreferences(markers)
            })
        }
        override fun finished(description: Description) {
            app.replaceTestContainer(null)
            database.close()
            if (description.methodName.startsWith("b_")) {
                app.deleteDatabase("ui-restart-probe-only.db")
                check(markers.edit().clear().commit())
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun a_saveExpenseThroughUi() {
        compose.waitTag("add_transaction")
        compose.addEvent(TransactionKind.EXPENSE, "199.50", "Synthetic Restart Lunch")
        compose.onNodeWithTag("metric_spending").assertMoneyEquals("₹199.50")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.addBudget("500")
        compose.budgetNode("budget_remaining_overall").assertMoneyEquals("₹300.50")
        check(markers.edit().putInt("writer_pid", Process.myPid()).commit())
    }

    @Test fun b_readExpenseInFreshProcess() {
        if (InstrumentationRegistry.getArguments().getString("requireFreshProcess") == "true") {
            assertNotEquals(markers.getInt("writer_pid", -1), Process.myPid())
        }
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("metric_spending").assertMoneyEquals("₹199.50")
        assertEquals(19_950L, runBlocking { repository.read().transactions.single().transaction.amountMinor })
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("budget_limit_overall").assertMoneyEquals("₹500.00")
        compose.budgetNode("budget_remaining_overall").assertMoneyEquals("₹300.50")
        assertEquals(50_000L, runBlocking { repository.read().budgets.single().value.limitMinor })
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.field("search_transactions", "Restart Lunch")
    }
}
