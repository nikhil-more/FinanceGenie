package com.financegenie.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.ui.LedgerViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

internal val uiBank = UUID(0, 1)
internal val uiCash = UUID(0, 2)
internal val uiCard = UUID(0, 3)
internal val uiClock: Clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC)
internal suspend fun seedUiAccounts(repository: LedgerRepository) {
    repository.saveAccount(LedgerAccount(uiBank, "Test Bank", AccountKind.BANK, LocalDate.of(2026, 11, 1)))
    repository.saveAccount(LedgerAccount(uiCash, "Test Cash", AccountKind.CASH, LocalDate.of(2026, 11, 1)))
    repository.saveAccount(LedgerAccount(uiCard, "Test Card", AccountKind.CREDIT_CARD, LocalDate.of(2026, 11, 1)))
    repository.saveCategory(LedgerCategory("food", "Food"))
    repository.saveCategory(LedgerCategory("shopping", "Shopping"))
    repository.saveCategory(LedgerCategory("groceries", "Groceries"))
}

internal typealias AppComposeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>
internal fun ComposeContentTestRule.waitTag(tag: String) = waitUntil(15_000) {
    runCatching { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
}
internal fun ComposeContentTestRule.waitNoTag(tag: String) = waitUntil(15_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
internal fun ComposeContentTestRule.field(tag: String, value: String) {
    waitTag(tag)
    repeat(3) { attempt ->
        try {
            onNodeWithTag(tag).performScrollTo()
            onNodeWithTag(tag).performTextReplacement(value)
            waitUntil(15_000) {
                runCatching { onNodeWithTag(tag).assertTextContains(value); true }.getOrDefault(false)
            }
            return
        } catch (error: AssertionError) {
            if (attempt == 2 || !error.message.orEmpty().contains("node is no longer in the tree", ignoreCase = true)) throw error
            // Keyboard/window transitions can detach a just-fetched semantics node.
            // Reacquire the same control; financial inputs/assertions are unchanged.
            waitForIdle()
        }
    }
}
internal fun ComposeContentTestRule.choose(tag: String, id: String) {
    onNodeWithTag(tag).performScrollTo().performClick()
    onNodeWithTag("choice_$id").performScrollTo().performClick()
}
internal fun ComposeContentTestRule.saveForm() {
    androidx.test.espresso.Espresso.closeSoftKeyboard()
    onNodeWithTag("save_form").assertIsDisplayed().assertIsEnabled().performClick()
    waitUntil(15_000) {
        onAllNodes(hasTestTag("ledger_form")).fetchSemanticsNodes().isEmpty() ||
            onAllNodes(hasTestTag("error_message")).fetchSemanticsNodes().isNotEmpty()
    }
    val errors = onAllNodes(hasTestTag("error_message")).fetchSemanticsNodes()
    check(errors.isEmpty()) { "Save displayed a validation error: ${errors.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text] }}" }
    onNodeWithTag("ledger_form").assertDoesNotExist()
    dismissNotice()
}
internal fun ComposeContentTestRule.dismissNotice() {
    // Dismiss the transient confirmation before interacting with a row beneath it.
    if (onAllNodes(hasContentDescription("Dismiss")).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithContentDescription("Dismiss").performClick()
    }
}
internal fun ComposeContentTestRule.addEvent(kind: TransactionKind, amount: String, merchant: String, account: UUID = uiBank,
    category: String = "food", destination: UUID? = null, purchase: UUID? = null, date: String = "2026-11-30") {
    onNodeWithTag("add_transaction").performClick()
    choose("field_kind", kind.name)
    field("field_amount", amount)
    field("field_date", date)
    choose("field_account", account.toString())
    if (kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND) choose("field_category", category)
    destination?.let { choose("field_destination", it.toString()) }
    purchase?.let { choose("field_purchase", it.toString()) }
    if (kind == TransactionKind.ADJUSTMENT) field("field_reason", "Synthetic correction")
    field("field_merchant", merchant)
    saveForm()
}

class ManagementUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun accountsCategoriesObservationsAndArchiveUseStableRecords() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("manage_accounts").performClick()
        compose.onNodeWithTag("add_account").performClick()
        compose.field("field_name", "Synthetic Card")
        compose.choose("field_kind", AccountKind.CREDIT_CARD.name)
        compose.field("field_amount", "100.25")
        compose.choose("field_sign", "true")
        compose.field("field_date", "2026-11-01")
        compose.saveForm()
        val account = runBlocking { data.repository.read().accounts.single() }
        assertEquals(-10_025L, account.value.openingBalanceMinor)
        compose.onNodeWithText("Amount owed").assertIsDisplayed()
        compose.onNodeWithTag("balance_${account.value.id}").assertTextEquals("₹100.25")
        compose.onNodeWithTag("observe_${account.value.id}").performScrollTo().performClick()
        compose.field("field_amount", "110.25")
        compose.saveForm()
        assertEquals(-11_025L, runBlocking { data.repository.read().observations.single().value.reportedBalanceMinor })
        compose.onNodeWithTag("archive_account_${account.value.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.repository.read().accounts.single().value.isArchived } }
        compose.onNodeWithTag("edit_account_${account.value.id}").performScrollTo().performClick()
        compose.field("field_name", "Renamed Card")
        compose.saveForm()
        assertTrue(runBlocking { data.repository.read().accounts.single().value.isArchived })
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("manage_categories").performClick()
        compose.onNodeWithTag("add_category").performClick()
        compose.field("field_name", "Food")
        compose.saveForm()
        val category = runBlocking { data.repository.read().categories.first { it.value.name == "Food" } }
        compose.onNodeWithTag("archive_category_${category.value.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.repository.read().categories.first { it.value.id == category.value.id }.value.isArchived } }
        compose.onNodeWithTag("edit_category_${category.value.id}").performScrollTo().performClick()
        compose.field("field_name", "Meals")
        compose.saveForm()
        val edited = runBlocking { data.repository.read().categories.first { it.value.id == category.value.id } }
        assertEquals("Meals", edited.value.name)
        assertTrue(edited.value.isArchived)
        compose.onNodeWithTag("archive_category_${category.value.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !data.repository.read().categories.first { it.value.id == category.value.id }.value.isArchived } }
        compose.onNodeWithTag("archive_category_uncategorized").assertDoesNotExist()
    }
}

class LedgerUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedUiAccounts)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun expenseEditDeleteUndoRestoreFiltersAndRecreation() {
        compose.waitTag("add_transaction")
        compose.addEvent(TransactionKind.EXPENSE, "249.50", "Synthetic Lunch")
        val id = runBlocking { data.repository.read().transactions.single().transaction.id }
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.onNodeWithTag("edit_$id").performScrollTo().performClick()
        compose.field("field_amount", "199.50")
        compose.onNodeWithTag("field_amount").assertTextContains("199.50")
        compose.runOnUiThread {
            val draft = androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java].editor.value
            assertEquals("199.50", (draft as com.financegenie.android.ui.TransactionDraft).amount)
        }
        compose.activityRule.scenario.recreate()
        compose.waitTag("ledger_form")
        compose.onNodeWithTag("field_amount").assertTextContains("199.50")
        compose.saveForm()
        assertEquals(19_950L, runBlocking { data.repository.read().transactions.single().transaction.amountMinor })
        compose.onNodeWithTag("delete_$id").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitUntil(10_000) { runBlocking { data.repository.read().transactions.single().transaction.isDeleted } }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { runBlocking { !data.repository.read().transactions.single().transaction.isDeleted } }
        compose.waitTag("delete_$id")
        compose.dismissNotice()
        compose.onNodeWithTag("delete_$id").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitUntil(10_000) { runBlocking { data.repository.read().transactions.single().transaction.isDeleted } }
        compose.dismissNotice()
        compose.onNodeWithTag("filter_deleted").performScrollTo().performClick()
        compose.onNodeWithTag("restore_$id").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !data.repository.read().transactions.single().transaction.isDeleted } }
        compose.dismissNotice()
        compose.field("search_transactions", "no match")
        compose.waitNoTag("transaction_$id")
        compose.onNodeWithTag("transaction_$id").assertDoesNotExist()
        compose.field("search_transactions", "Lunch")
        compose.choose("filter_account", uiCash.toString())
        compose.waitNoTag("transaction_$id")
        compose.onNodeWithTag("transaction_$id").assertDoesNotExist()
        compose.choose("filter_account", uiBank.toString())
        compose.choose("filter_category", "food")
        compose.waitTag("transaction_$id")
        compose.onNodeWithTag("transaction_$id").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("transaction_$id").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Manual").assertIsDisplayed()
    }

    @Test fun invalidFieldsStaleEditsAndRepeatedSaveCannotDuplicateOrOverwrite() {
        compose.waitTag("add_transaction")
        compose.onNodeWithTag("add_transaction").performClick()
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText("Choose an account.").assertIsDisplayed()
        compose.choose("field_account", uiBank.toString())
        compose.field("field_amount", "12.345")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText("Enter a valid INR amount with no more than two decimal places.").assertIsDisplayed()
        compose.field("field_amount", "10")
        compose.field("field_date", "2026-12-01")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText("Recorded dates cannot be in the future.").assertIsDisplayed()
        compose.field("field_date", "2026-11-30")
        compose.runOnUiThread {
            val model = androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
            model.saveEditor()
            model.saveEditor()
        }
        compose.waitUntil(10_000) { runBlocking { data.repository.read().transactions.size == 1 } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("ledger_form")).fetchSemanticsNodes().isEmpty() }
        compose.dismissNotice()
        val record = runBlocking { data.repository.read().transactions.single() }
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.onNodeWithTag("edit_${record.transaction.id}").performScrollTo().performClick()
        runBlocking { data.repository.applyTransactions(listOf(com.financegenie.android.data.TransactionWrite(
            record.transaction.copy(amountMinor = 2_000), record.details, record.metadata.revision))) }
        compose.field("field_amount", "30")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText("This record changed. Close and reopen the form before saving.").assertIsDisplayed()
        assertEquals(2_000L, runBlocking { data.repository.read().transactions.single().transaction.amountMinor })
    }

    @Test fun goldenSalaryPurchaseRefundTransfersCardPaymentAndAdjustment() {
        compose.waitTag("add_transaction")
        val fixtures = org.json.JSONObject(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("ledger/november-baseline.json").bufferedReader().use { it.readText() }).getJSONArray("transactions")
        val names = listOf("Synthetic Salary", "Synthetic Groceries", "Synthetic Refund", "Synthetic Cash Withdrawal", "Synthetic Card Purchase", "Synthetic Card Payment")
        val generatedIds = mutableMapOf<String, UUID>()
        for (index in 0 until fixtures.length()) {
            val event = fixtures.getJSONObject(index)
            compose.addEvent(TransactionKind.valueOf(event.getString("kind")),
                java.math.BigDecimal(event.getString("amountMinor")).movePointLeft(2).toPlainString(), names[index],
                UUID.fromString(event.getString("accountId")), event.optString("categoryId", "food"),
                event.optString("destinationAccountId").takeIf { it.isNotEmpty() }?.let(UUID::fromString),
                generatedIds[event.optString("originalExpenseId")], event.getString("effectiveDate"))
            generatedIds[event.getString("id")] = runBlocking { data.repository.read().transactions.first { it.details.merchant == names[index] }.transaction.id }
        }
        val expense = generatedIds.getValue("00000000-0000-0000-0000-000000000102")
        val report = LedgerCalculator(uiClock).monthlyReport(runBlocking { data.repository.read().snapshot }, YearMonth.of(2026, 11))
        assertEquals(349_900L, report.netSpendingMinor)
        assertEquals(6_000_000L, report.incomeMinor)
        assertEquals(mapOf(uiBank to 5_550_100L, uiCash to 100_000L, uiCard to 0L), report.accountPositionsMinor)
        compose.onNodeWithTag("metric_spending").performScrollTo().assertTextEquals("₹3,499.00")
        compose.onNodeWithTag("overview_list").performScrollToNode(hasTestTag("metric_refunds"))
        compose.onNodeWithTag("metric_refunds").performScrollTo().assertTextEquals("₹250.75")
        compose.onNodeWithTag("overview_list").performScrollToNode(hasTestTag("metric_income"))
        compose.onNodeWithTag("metric_income").performScrollTo().assertTextEquals("₹60,000.00")
        compose.addEvent(TransactionKind.ADJUSTMENT, "10", "Synthetic Adjustment")
        val after = LedgerCalculator(uiClock).monthlyReport(runBlocking { data.repository.read().snapshot }, YearMonth.of(2026, 11))
        assertEquals(349_900L, after.netSpendingMinor)
        assertEquals(6_000_000L, after.incomeMinor)
        assertEquals(5_551_100L, after.accountPositionsMinor.getValue(uiBank))
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.field("search_transactions", "Groceries")
        compose.onNodeWithTag("edit_$expense").performScrollTo().performClick()
        compose.field("field_amount", "200")
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText("Linked refunds cannot exceed the original expense. Correct the refunds first.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("delete_$expense").performScrollTo().performClick()
        compose.onNodeWithText("Delete purchase and refunds").assertIsDisplayed()
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitUntil(10_000) { runBlocking { data.repository.read().transactions.count { it.transaction.isDeleted } == 2 } }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { runBlocking { data.repository.read().transactions.none { it.transaction.isDeleted } } }
        assertFalse(runBlocking { data.repository.read().transactions.first { it.transaction.id == expense }.transaction.isDeleted })
    }

    @Test fun importedOriginSurvivesManualCorrection() {
        compose.waitTag("add_transaction")
        val id = UUID(0, 500)
        runBlocking {
            val event = com.financegenie.android.domain.LedgerTransaction(id, TransactionKind.EXPENSE, 100, uiBank,
                LocalDate.of(2026, 11, 20), categoryId = "food")
            data.repository.applyTransactions(listOf(com.financegenie.android.data.TransactionWrite(event,
                com.financegenie.android.data.TransactionDetails("Synthetic CSV", source = com.financegenie.android.data.TransactionSource.CSV))),
                listOf(com.financegenie.android.data.SourceEvidenceInput(UUID(0, 501), id,
                    com.financegenie.android.data.TransactionSource.CSV, "synthetic-ui-csv-row")))
        }
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.onNodeWithTag("edit_$id").performScrollTo().performClick()
        compose.field("field_amount", "2")
        compose.saveForm()
        compose.onNodeWithText("Imported from CSV").assertIsDisplayed()
        assertEquals(com.financegenie.android.data.TransactionSource.CSV,
            runBlocking { data.repository.read().transactions.single().details.source })
    }

    @Test fun unlinkedRefundNeedsConfirmationAndArchivedReferencesRemainEditable() {
        compose.waitTag("add_transaction")
        compose.addEvent(TransactionKind.EXPENSE, "100", "Synthetic Archived Purchase")
        val before = runBlocking { data.repository.read() }
        val event = before.transactions.single().transaction
        runBlocking {
            val account = before.accounts.first { it.value.id == uiBank }
            data.repository.saveAccount(account.value.copy(isArchived = true), account.metadata.revision)
            val category = before.categories.first { it.value.id == "food" }
            data.repository.saveCategory(category.value.copy(isArchived = true), category.metadata.revision)
        }
        compose.onNodeWithTag("tab_transactions").performClick()
        compose.onNodeWithTag("edit_${event.id}").performScrollTo().performClick()
        compose.field("field_amount", "90")
        compose.saveForm()
        assertEquals(9_000L, runBlocking { data.repository.read().transactions.single().transaction.amountMinor })
        compose.onNodeWithTag("add_transaction").performClick()
        compose.choose("field_kind", TransactionKind.REFUND.name)
        compose.field("field_amount", "10")
        compose.onNodeWithTag("field_account").performScrollTo().performClick()
        compose.onNodeWithTag("choice_$uiBank").assertDoesNotExist()
        compose.onNodeWithTag("choice_$uiCash").performClick()
        compose.onNodeWithTag("save_form").performClick()
        compose.onNodeWithText("Confirm the unlinked refund or choose its original purchase.").assertIsDisplayed()
        compose.onNodeWithTag("confirm_unlinked").performScrollTo().performClick()
        compose.saveForm()
        assertTrue(runBlocking { data.repository.read().transactions.first { it.transaction.kind == TransactionKind.REFUND }.transaction.unlinkedRefundConfirmed })
    }
}
