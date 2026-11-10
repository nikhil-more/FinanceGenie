package com.financegenie.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.domain.*
import com.financegenie.android.recovery.BackupCodec
import com.financegenie.android.ui.LedgerViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.YearMonth
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses the production container in a separate, non-debuggable release-like app. */
class ReleaseAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freshOfflineGoldenEntryBudgetEditDeleteRecoveryAndLocalDeletion() = runBlocking {
        val app = compose.activity.application as FinanceGenieApplication
        assumeTrue("Disposable release candidate only", app.packageName.endsWith(".acceptance"))
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val forbidden = setOf("android.permission.INTERNET", "android.permission.READ_SMS", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.POST_NOTIFICATIONS")
        @Suppress("DEPRECATION") val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.none { it in forbidden })
        val container = app.container
        val profiles = requireNotNull(container.profiles)
        fun model() = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
        assertTrue(container.repository.read().accounts.isEmpty())
        compose.runOnUiThread {
            // Keep this isolated UI journey awake without changing phone settings.
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.waitTag("add_transaction")
        // Keep the golden fixture reproducible after November without replacing
        // the production clock, repository or application container.
        compose.runOnUiThread { model().filter { it.copy(month = YearMonth.of(2026, 11)) } }
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("manage_accounts").performScrollTo().performClick()
        for ((name, kind) in listOf("Synthetic Bank" to AccountKind.BANK, "Synthetic Cash" to AccountKind.CASH, "Synthetic Card" to AccountKind.CREDIT_CARD)) {
            compose.onNodeWithTag("add_account").performClick()
            compose.field("field_name", name); compose.choose("field_kind", kind.name)
            compose.field("field_amount", "0")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.field("field_date", "2026-11-01"); compose.saveForm()
        }
        val accounts = container.repository.read().accounts.associate { it.value.name to it.value.id }
        val bank = accounts.getValue("Synthetic Bank"); val cash = accounts.getValue("Synthetic Cash"); val card = accounts.getValue("Synthetic Card")
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("manage_categories").performScrollTo().performClick()
        compose.onNodeWithTag("add_category").performClick(); compose.field("field_name", "Food"); compose.saveForm()
        val food = container.repository.read().categories.single { it.value.name == "Food" }.value.id
        compose.onNodeWithTag("tab_overview").performClick()
        compose.addEvent(TransactionKind.INCOME, "60000", "Synthetic Salary", bank, date = "2026-11-02")
        compose.addEvent(TransactionKind.EXPENSE, "2750.25", "Synthetic Groceries", bank, food, date = "2026-11-02")
        val purchase = container.repository.read().transactions.single { it.details.merchant == "Synthetic Groceries" }.transaction.id
        compose.addEvent(TransactionKind.REFUND, "250.75", "Synthetic Refund", bank, food, purchase = purchase, date = "2026-11-03")
        compose.addEvent(TransactionKind.TRANSFER, "1000", "Synthetic Cash Withdrawal", bank, destination = cash, date = "2026-11-03")
        compose.addEvent(TransactionKind.EXPENSE, "999.50", "Synthetic Card Purchase", card, food, date = "2026-11-03")
        compose.addEvent(TransactionKind.TRANSFER, "999.50", "Synthetic Card Payment", bank, destination = card, date = "2026-11-03")
        fun report() = runBlocking { LedgerCalculator(container.clock).monthlyReport(container.repository.read().snapshot, YearMonth.of(2026, 11)) }
        assertEquals(349_900L, report().netSpendingMinor)
        assertEquals(mapOf(bank to 5_550_100L, cash to 100_000L, card to 0L), report().accountPositionsMinor)
        assertEquals(5_650_100L, report().totalTrackedPositionMinor)
        compose.onNodeWithTag("tab_budgets").performClick()
        compose.onNodeWithTag("add_budget").performScrollTo().performClick(); compose.field("field_amount", "5000"); compose.saveForm()
        compose.onNodeWithTag("budget_list").performScrollToNode(hasTestTag("budget_remaining_overall"))
        compose.onNodeWithTag("budget_remaining_overall").assertMoneyEquals("₹1,501.00")
        compose.onNodeWithTag("tab_transactions").performClick()
        fun transactionAction(tag: String) {
            compose.waitTag("transaction_list")
            compose.onNodeWithTag("transaction_list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo().performClick()
        }
        transactionAction("edit_$purchase")
        compose.field("field_amount", "2500.25"); compose.saveForm()
        assertEquals(324_900L, report().netSpendingMinor)
        transactionAction("edit_$purchase")
        compose.field("field_amount", "2750.25"); compose.saveForm()
        assertEquals(349_900L, report().netSpendingMinor)
        transactionAction("delete_$purchase")
        compose.onNodeWithTag("confirm_delete").performClick()
        compose.waitUntil(15_000) { runBlocking { container.repository.read().transactions.count { it.transaction.isDeleted } == 2 } }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(15_000) { runBlocking { container.repository.read().transactions.none { it.transaction.isDeleted } } }
        compose.dismissNotice()
        assertEquals(349_900L, report().netSpendingMinor)

        // Process-local zone changes exercise the actual Android engine without changing the phone.
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(349_900L, report().netSpendingMinor)
            assertEquals(java.time.LocalDate.of(2026, 11, 1), LedgerDates.effectiveDate(java.time.Instant.parse("2026-10-31T18:30:00Z")))
        } finally { TimeZone.setDefault(zone) }
        val source = profiles.backup()
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(source, it, container.clock) }.toByteArray()
        File(app.filesDir, "acceptance-smoke-only.json").writeBytes(bytes)
        compose.onNodeWithTag("tab_settings").performClick(); compose.onNodeWithTag("manage_recovery").performScrollTo().performClick()
        compose.runOnUiThread { model().recovery!!.read { "{truncated".byteInputStream() } }
        compose.waitTag("recovery_message")
        assertEquals(source.ledger, container.repository.read())
        compose.onNodeWithTag("recovery_delete").performScrollTo().performClick()
        compose.waitTag("recovery_confirm"); compose.onNodeWithTag("recovery_confirm").performClick()
        compose.waitUntil(20_000) { runCatching { compose.onAllNodes(hasTestTag("add_transaction")).fetchSemanticsNodes().isNotEmpty() && runBlocking { container.repository.read().accounts.isEmpty() } }.getOrDefault(false) }
        compose.onNodeWithTag("tab_settings").performClick(); compose.onNodeWithTag("manage_recovery").performScrollTo().performClick()
        compose.runOnUiThread { model().recovery!!.read { bytes.inputStream() } }
        compose.waitTag("recovery_validated")
        compose.onNodeWithTag("recovery_restore").performScrollTo().performClick()
        compose.waitUntil(20_000) { runCatching { compose.onAllNodes(hasTestTag("add_transaction")).fetchSemanticsNodes().isNotEmpty() && runBlocking { container.repository.read().transactions.size == 6 } }.getOrDefault(false) }
        val actual = profiles.backup()
        assertEquals(source.forNewProfile(actual.ledger.settings.localId).ordered(), actual.copy(createdAtMillis = source.createdAtMillis).ordered())
        File(app.filesDir, "acceptance-smoke-only.pid").writeText(Process.myPid().toString())
        assertEquals(349_900L, report().netSpendingMinor)
    }
}

class ReleaseAcceptanceReadTest {
    @Test fun aFreshProcessReadsTheCompleteRestoredGoldenLedger() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FinanceGenieApplication
        assumeTrue("Disposable release candidate only", app.packageName.endsWith(".acceptance"))
        assertNotEquals(File(app.filesDir, "acceptance-smoke-only.pid").readText().toInt(), Process.myPid())
        val source = File(app.filesDir, "acceptance-smoke-only.json").inputStream().use { BackupCodec.read(it, app.container.clock) }
        val actual = requireNotNull(app.container.profiles).backup()
        assertEquals(source.forNewProfile(actual.ledger.settings.localId).ordered(), actual.copy(createdAtMillis = source.createdAtMillis).ordered())
        assertEquals(6, actual.ledger.transactions.size)
        assertEquals(349_900L, LedgerCalculator(app.container.clock).monthlyReport(actual.ledger.snapshot, YearMonth.of(2026, 11)).netSpendingMinor)
    }
}
