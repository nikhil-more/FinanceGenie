package com.financegenie.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.financegenie.android.csv.CsvImportRequest
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.ui.LedgerViewModel
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

private const val csvHeader = "date,amount,direction,merchant,category,reference\n"
private val csvSimple = csvHeader + "2026-11-02,1.25,DEBIT,Synthetic CSV lunch,Food,SYN-ONE\n"
private fun AppComposeRule.csvModel() = ViewModelProvider(activity)[LedgerViewModel::class.java]
private fun AppComposeRule.openCsv() {
    if (onAllNodes(hasTestTag("csv_screen")).fetchSemanticsNodes().isNotEmpty()) return
    waitTag("tab_settings")
    onNodeWithTag("tab_settings").performClick()
    waitUntil(15_000) { onAllNodes(hasTestTag("manage_imports") or hasTestTag("csv_screen")).fetchSemanticsNodes().isNotEmpty() }
    if (onAllNodes(hasTestTag("csv_screen")).fetchSemanticsNodes().isEmpty()) onNodeWithTag("manage_imports").performScrollTo().performClick()
    waitTag("csv_screen")
}
private fun AppComposeRule.loadCsv(text: String) {
    val model = csvModel()
    runOnUiThread { model.csv.read { text.byteInputStream() } }
    waitTag("csv_preview")
    choose("csv_account", uiBank.toString())
    onNodeWithTag("csv_preview").performScrollTo().performClick()
    waitTag("csv_count_parsed")
}
private fun AppComposeRule.csvCount(tag: String, expected: String) {
    onNodeWithTag("csv_screen").performScrollToNode(hasTestTag(tag))
    waitUntil(15_000) { onAllNodes(hasTestTag(tag) and hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(tag).assertTextEquals(expected)
}
private fun AppComposeRule.bulkCsv() {
    onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_bulk_debits"))
    onNodeWithTag("csv_bulk_debits").performClick()
    onNodeWithTag("csv_confirm_bulk").performClick()
}
private fun AppComposeRule.reviewCsv(number: Int) {
    onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_review_$number"))
    onNodeWithTag("csv_review_$number").performClick()
}
private fun AppComposeRule.commitCsv() {
    onNodeWithTag("csv_screen").performScrollToNode(hasTestTag("csv_commit"))
    onNodeWithTag("csv_commit").assertIsEnabled().performClick()
    onNodeWithTag("csv_confirm_action").performClick()
}
private fun AppComposeRule.batchAction(id: UUID, restore: Boolean = false) {
    val tag = "csv_${if (restore) "restore" else "undo"}_$id"
    onNodeWithTag("csv_screen").performScrollToNode(hasTestTag(tag))
    onNodeWithTag(tag).performClick()
    onNodeWithTag("csv_confirm_action").performClick()
}

class CsvUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedUiAccounts)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cancellingAnInProgressReadClosesTheStreamAndWritesNothing() {
        compose.openCsv()
        val before = runBlocking { data.repository.read() }
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val model = compose.csvModel()
        compose.runOnUiThread {
            model.csv.read { object : java.io.InputStream() {
                override fun read(): Int { entered.countDown(); java.util.concurrent.CountDownLatch(1).await(30, java.util.concurrent.TimeUnit.SECONDS); return -1 }
                override fun close() { closed.set(true) }
            } }
        }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        compose.onNodeWithTag("csv_cancel").performScrollTo().performClick()
        compose.waitUntil(10_000) { closed.get() && !model.csv.state.value.busy }
        assertEquals(before, runBlocking { data.repository.read() })
    }

    @Test fun hundredRowsReviewCreditsCommitUndoReplayAndRestoreConverge() {
        compose.openCsv()
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("csv/acceptance-100.csv").bufferedReader().use { it.readText() }
        compose.loadCsv(text)
        compose.csvCount("csv_count_parsed", "CSV rows: 100")
        compose.csvCount("csv_count_invalid", "Invalid: 10")
        compose.csvCount("csv_count_review", "Needs review: 90")
        compose.activityRule.scenario.recreate()
        compose.waitTag("csv_count_review")
        compose.csvCount("csv_count_accepted", "Accepted: 0")
        compose.bulkCsv()
        compose.csvCount("csv_count_accepted", "Accepted: 80")
        compose.reviewCsv(82)
        compose.onNodeWithTag("csv_accept_row").performClick()
        compose.csvCount("csv_count_accepted", "Accepted: 81")
        compose.reviewCsv(83)
        compose.choose("csv_kind", TransactionKind.TRANSFER.name)
        compose.choose("csv_other_account", uiCash.toString())
        compose.activityRule.scenario.recreate()
        compose.waitTag("csv_other_account")
        compose.onNodeWithTag("csv_other_account").assertTextContains("Test Cash")
        compose.onNodeWithTag("csv_accept_row").performClick()
        compose.csvCount("csv_count_accepted", "Accepted: 82")
        compose.reviewCsv(84)
        compose.choose("csv_kind", TransactionKind.REFUND.name)
        compose.onNodeWithTag("csv_accept_row").assertIsNotEnabled()
        compose.onNodeWithTag("csv_confirm_unlinked").performScrollTo().performClick()
        compose.onNodeWithTag("csv_accept_row").performClick()
        compose.csvCount("csv_count_accepted", "Accepted: 83")
        compose.commitCsv()
        compose.waitUntil(15_000) { runBlocking { data.repository.read().importBatches.size == 1 } }
        val committed = runBlocking { data.repository.read() }
        val id = committed.importBatches.single().id
        assertEquals(83, committed.transactions.size)
        compose.onNodeWithTag("tab_overview").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("metric_spending") and hasMoneyText("₹765.50")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("metric_spending").assertMoneyEquals("₹765.50")
        compose.openCsv()
        compose.batchAction(id)
        compose.waitUntil(15_000) { runBlocking { data.repository.read().importBatches.single().isUndone } }
        assertTrue(runBlocking { data.repository.read().transactions.all { it.transaction.isDeleted } })
        compose.loadCsv(text)
        compose.onNodeWithTag("csv_message").performScrollTo().assertTextEquals(compose.activity.getString(R.string.csv_undone_replay))
        compose.onNodeWithTag("csv_commit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("csv_cancel").performScrollTo().performClick()
        compose.batchAction(id, restore = true)
        compose.waitUntil(15_000) { runBlocking { !data.repository.read().importBatches.single().isUndone } }
        assertEquals(83, runBlocking { data.repository.read().transactions.count { !it.transaction.isDeleted } })
    }

    @Test fun cancelledPreviewAndStaleConfirmationNeverWriteFinancialRows() {
        compose.openCsv()
        val before = runBlocking { data.repository.read() }
        compose.loadCsv(csvSimple)
        compose.bulkCsv()
        compose.csvCount("csv_count_accepted", "Accepted: 1")
        compose.onNodeWithTag("csv_cancel").performScrollTo().performClick()
        compose.waitNoTag("csv_preview")
        assertEquals(before, runBlocking { data.repository.read() })
        compose.loadCsv(csvSimple)
        compose.bulkCsv()
        compose.csvCount("csv_count_accepted", "Accepted: 1")
        compose.onNodeWithTag("csv_commit").performScrollTo().performClick()
        val category = runBlocking { data.repository.read().categories.single { it.value.id == "food" } }
        runBlocking { data.repository.saveCategory(category.value.copy(name = "Updated Food"), category.metadata.revision) }
        compose.onNodeWithTag("csv_confirm_action").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("csv_message") and hasText(compose.activity.getString(R.string.csv_stale))).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { data.repository.read().transactions.isEmpty() })
        assertTrue(runBlocking { data.repository.read().importBatches.isEmpty() })
    }

    @Test fun splitColumnsAndExplicitDateFormatLeaveCreditsForReview() {
        compose.openCsv()
        val text = "date,debit,credit,merchant\n04/11/2026,1.25,0,Synthetic split debit\n04/11/2026,,2.00,Synthetic split credit\n04/11/2026,1,2,Synthetic invalid\n"
        val model = compose.csvModel()
        compose.runOnUiThread { model.csv.read { text.byteInputStream() } }
        compose.waitTag("csv_preview")
        compose.choose("csv_account", uiBank.toString())
        compose.choose("csv_mode", "DEBIT_CREDIT_COLUMNS")
        compose.choose("csv_date_format", "DAY_FIRST")
        compose.onNodeWithTag("csv_preview").performScrollTo().performClick()
        compose.csvCount("csv_count_invalid", "Invalid: 1")
        compose.csvCount("csv_count_review", "Needs review: 2")
        compose.bulkCsv()
        compose.csvCount("csv_count_accepted", "Accepted: 1")
        compose.csvCount("csv_count_review", "Needs review: 1")
        assertTrue(runBlocking { data.repository.read().transactions.isEmpty() })
    }
}

class CsvMatchUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = {
        seedUiAccounts(it)
        it.applyTransactions(listOf(TransactionWrite(com.financegenie.android.data.local.storageExpense(amount = 125), TransactionDetails("Synthetic CSV lunch"))))
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun matchedManualMovementKeepsItsFieldsAndUndoOnlyDetachesEvidence() {
        val before = runBlocking { data.repository.read().transactions.single() }
        compose.openCsv()
        compose.loadCsv(csvSimple)
        compose.csvCount("csv_count_matches", "Rows with possible matches: 1")
        compose.bulkCsv()
        compose.csvCount("csv_count_accepted", "Accepted: 0")
        compose.reviewCsv(2)
        compose.choose("csv_existing", before.transaction.id.toString())
        compose.onNodeWithTag("csv_attach").performScrollTo().performClick()
        compose.csvCount("csv_count_accepted", "Accepted: 1")
        compose.commitCsv()
        compose.waitUntil(15_000) { runBlocking { data.repository.read().importBatches.size == 1 } }
        val committed = runBlocking { data.repository.read() }
        assertEquals(before, committed.transactions.single())
        compose.batchAction(committed.importBatches.single().id)
        compose.waitUntil(15_000) { runBlocking { data.repository.read().importBatches.single().isUndone } }
        assertEquals(before, runBlocking { data.repository.read().transactions.single() })
    }
}

class CsvFailureUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedUiAccounts, decorate = { repository ->
        object : LedgerRepository by repository {
            override suspend fun commitCsv(request: CsvImportRequest): UUID = throw android.database.sqlite.SQLiteFullException("Synthetic CSV disk failure")
        }
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun storageFailureKeepsPreviewAndShowsSanitizedActionableError() {
        compose.openCsv()
        compose.loadCsv(csvSimple)
        compose.bulkCsv()
        compose.csvCount("csv_count_accepted", "Accepted: 1")
        compose.commitCsv()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("csv_message") and hasText(compose.activity.getString(R.string.csv_selection_error))).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { data.repository.read().transactions.isEmpty() })
        compose.onNodeWithTag("csv_commit").performScrollTo().assertIsEnabled()
    }
}

class CsvPickerUiTest {
    @get:Rule(order = 0) val data = TestLedgerRule(seed = ::seedUiAccounts)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun systemPickerReadsOnlyThePreparedSyntheticDocument() {
        val fileName = InstrumentationRegistry.getArguments().getString("csvPickerFileName")
        assumeTrue("Run the CSV driver to supply its unique synthetic picker file", fileName != null)
        require(fileName!!.matches(Regex("FinanceGenie-step7-synthetic-[a-f0-9]+\\.csv")))
        compose.openCsv()
        compose.onNodeWithTag("csv_choose_file").performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 5_000))
        fun clickFresh(selector: androidx.test.uiautomator.BySelector, timeout: Long = 5_000): Boolean {
            repeat(5) {
                try {
                    val node = device.wait(Until.findObject(selector), timeout) ?: return false
                    node.click()
                    return true
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // DocumentsUI can replace nodes while its directory listing settles.
                    device.waitForIdle(5_000)
                }
            }
            return false
        }
        if (device.hasObject(By.desc("Show roots"))) assertTrue(clickFresh(By.desc("Show roots")))
        assertTrue("Wait for and select Downloads after the root drawer opens", clickFresh(By.text("Downloads")))
        assertTrue("The driver-owned synthetic file must be available", clickFresh(By.text(fileName), 15_000))
        compose.waitTag("csv_preview")
        val expected = InstrumentationRegistry.getInstrumentation().context.assets.open("csv/acceptance-100.csv").use(com.financegenie.android.csv.CsvReader::read)
        assertEquals("Picker must return only the prepared synthetic file", expected.digest, compose.csvModel().csv.state.value.document!!.digest)
        compose.choose("csv_account", uiBank.toString())
        compose.onNodeWithTag("csv_preview").performScrollTo().performClick()
        compose.csvCount("csv_count_parsed", "CSV rows: 100")
        compose.csvCount("csv_count_invalid", "Invalid: 10")
        compose.onNodeWithTag("csv_cancel").performScrollTo().performClick()
        assertTrue(runBlocking { data.repository.read().transactions.isEmpty() })
    }
}
