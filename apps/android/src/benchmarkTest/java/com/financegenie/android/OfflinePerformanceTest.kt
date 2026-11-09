package com.financegenie.android

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.domain.DateRange
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.ui.LedgerViewModel
import com.financegenie.android.ui.TransactionDraft
import com.financegenie.android.ui.money
import java.time.YearMonth
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Included only when financegenieTestBuildType=benchmark; all data belongs to .benchmark. */
class OfflinePerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun summarySearchAndSaveToVisibleWithTenThousandRecords() = runBlocking {
        val app = compose.activity.application as BenchmarkApplication
        val repository = app.container.repository
        val calculator = LedgerCalculator(app.container.clock)
        val range = DateRange.month(YearMonth.of(2026, 11))
        compose.waitTag("add_transaction")
        val snapshot = repository.read()
        assertEquals(10_000, snapshot.transactions.size)
        val initial = calculator.report(snapshot.snapshot, range)
        val readTimes = mutableListOf<Double>()
        val summaryTimes = mutableListOf<Double>()
        val searchTimes = mutableListOf<Double>()
        val saveTimes = mutableListOf<Double>()
        var sampledPssKb = Debug.getPss()
        repeat(30) {
            var start = SystemClock.elapsedRealtimeNanos()
            assertEquals(10_000, repository.read().transactions.size)
            readTimes += millisSince(start)
            start = SystemClock.elapsedRealtimeNanos()
            val report = calculator.report(app.freshRepository().read().snapshot, range)
            assertEquals(initial.netSpendingMinor, report.netSpendingMinor)
            summaryTimes += millisSince(start)
            start = SystemClock.elapsedRealtimeNanos()
            assertEquals(50, repository.transactions(range, search = "Synthetic benchmark", limit = 50).size)
            searchTimes += millisSince(start)
            sampledPssKb = maxOf(sampledPssKb, Debug.getPss())
        }
        repeat(30) { index ->
            // Draft entry is outside the measurement; the real Save action commits and renders.
            compose.runOnUiThread {
                val model = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
                model.newTransaction()
                val draft = model.editor.value as TransactionDraft
                model.updateEditor(draft.copy(amount = "0.01", accountId = UUID(0, 1), merchant = "Synthetic benchmark save $index"))
            }
            compose.waitTag("save_form")
            val expected = money(initial.netSpendingMinor + index + 1)
            val start = SystemClock.elapsedRealtimeNanos()
            compose.onNodeWithTag("save_form").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("metric_spending") and hasText(expected)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            saveTimes += millisSince(start)
            compose.dismissNotice()
            sampledPssKb = maxOf(sampledPssKb, Debug.getPss())
        }
        assertEquals(10_030, repository.read().transactions.size)
        val result = JSONObject().put("records", 10_000).put("operations", 30)
            .put("snapshotReadMs", stats(readTimes)).put("summaryIncludingReadMs", stats(summaryTimes))
            .put("pagedSearchMs", stats(searchTimes)).put("saveToVisibleMs", stats(saveTimes))
            .put("sampledPeakPssKb", sampledPssKb)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("financegenie_performance", result.toString())
        })
    }

    private fun millisSince(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun stats(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        return JSONObject().put("runs", JSONArray(values)).put("p50", sorted[ceil(sorted.size * 0.50).toInt() - 1])
            .put("p95", sorted[ceil(sorted.size * 0.95).toInt() - 1]).put("max", sorted.last())
    }
}
