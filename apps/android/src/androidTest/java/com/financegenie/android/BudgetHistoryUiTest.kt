package com.financegenie.android

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.data.local.storageClock
import com.financegenie.android.data.local.storageExpense
import com.financegenie.android.domain.TransactionKind
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class BudgetHistoryUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(clock = storageClock, seed = { repository ->
        seedUiAccounts(repository)
        val purchase = storageExpense(amount = 400_000)
        val refund = purchase.copy(id = java.util.UUID(0, 101), kind = TransactionKind.REFUND, amountMinor = 100_000,
            effectiveDate = LocalDate.of(2026, 12, 1), originalExpenseId = purchase.id)
        val transfer = purchase.copy(id = java.util.UUID(0, 102), kind = TransactionKind.TRANSFER, amountMinor = 150_000,
            categoryId = null, destinationAccountId = uiCash)
        repository.applyTransactions(listOf(purchase, refund, transfer).map { TransactionWrite(it) })
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun laterRefundAndBackdatedCorrectionRecalculateOnlyTheirMonths() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("previous_month").performClick()
        compose.addBudget("5000")
        compose.addBudget("5000", "food")
        compose.budgetNode("budget_remaining_category:food").assertMoneyEquals("₹1,000.00")
        compose.budgetNode("copy_budgets").performClick()
        compose.onNodeWithTag("confirm_budget_copy").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("confirm_budget_copy")).fetchSemanticsNodes().isEmpty() }
        compose.dismissNotice()
        compose.budgetNode("budget_expenses_category:food").assertMoneyEquals("₹0.00")
        compose.budgetNode("budget_refunds_category:food").assertMoneyEquals("₹1,000.00")
        compose.budgetNode("budget_spending_category:food").assertMoneyEquals("-₹1,000.00")
        compose.budgetNode("budget_remaining_category:food").assertMoneyEquals("₹6,000.00")
        compose.budgetNode("previous_month").performClick()
        compose.budgetNode("budget_remaining_overall").assertMoneyEquals("₹1,000.00")
        compose.onNodeWithTag("tab_transactions").performClick()
        val purchase = runBlocking { data.repository.read().transactions.first { it.transaction.kind == TransactionKind.EXPENSE }.transaction.id }
        compose.onNodeWithTag("edit_$purchase").performScrollTo().performClick()
        compose.field("field_date", "2026-12-02")
        compose.field("field_amount", "5500")
        compose.saveForm()
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.budgetNode("budget_remaining_category:food").assertMoneyEquals("₹5,000.00")
        compose.budgetNode("next_month").performClick()
        compose.budgetNode("budget_spending_category:food").assertMoneyEquals("₹4,500.00")
        compose.budgetNode("budget_remaining_category:food").assertMoneyEquals("₹500.00")
    }
}
