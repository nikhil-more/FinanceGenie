package com.financegenie.android

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.csv.*
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.domain.TransactionKind
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CsvImportPerformanceTest {
    @Test fun tenThousandRowsCommitAtomicallyWithExactVerifiedTotals() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as BenchmarkApplication
        val repository = app.container.repository
        val before = repository.read()
        assertEquals(10_000, before.transactions.size)
        val calculator = LedgerCalculator(app.container.clock)
        val original = calculator.monthlyReport(before.snapshot, YearMonth.of(2026, 11))
        val bytes = buildString {
            append("date,amount,direction,merchant,category,reference\n")
            repeat(10_000) { append("2026-11-30,0.01,DEBIT,Synthetic CSV performance $it,,SYN-PERF-$it\n") }
        }.toByteArray(Charsets.UTF_8)
        val started = SystemClock.elapsedRealtimeNanos()
        val document = CsvReader.read(bytes.inputStream())
        val parsed = SystemClock.elapsedRealtimeNanos()
        val empty = CsvImportRequest(document, CsvMapping.forHeaders(document.headers), UUID(0, 1), emptyMap(), before.settings.revision)
        val preview = planCsvImport(before, empty, app.container.clock)
        assertEquals(0, preview.duplicateCount)
        val accepted = empty.copy(decisions = preview.rows.associate {
            it.parsed.number to CsvDecision(CsvAction.CREATE, TransactionKind.EXPENSE, it.parsed.categoryId)
        })
        val reviewed = planCsvImport(before, accepted, app.container.clock)
        assertEquals(10_000, reviewed.acceptedCount)
        assertTrue(reviewed.canCommit)
        val planned = SystemClock.elapsedRealtimeNanos()
        val batch = repository.commitCsv(accepted)
        val committed = SystemClock.elapsedRealtimeNanos()
        val after = repository.read()
        assertEquals(20_000, after.transactions.size)
        assertEquals(10_000, after.importBatches.single { it.id == batch }.acceptedCount)
        assertEquals(10_000, after.evidence.count { it.input.importBatchId == batch })
        val result = calculator.monthlyReport(after.snapshot, YearMonth.of(2026, 11))
        assertEquals(original.netSpendingMinor + 10_000, result.netSpendingMinor)
        assertEquals(original.accountPositionsMinor.getValue(UUID(0, 1)) - 10_000, result.accountPositionsMinor.getValue(UUID(0, 1)))
        val verified = SystemClock.elapsedRealtimeNanos()
        fun ms(from: Long, to: Long) = (to - from) / 1_000_000.0
        val report = JSONObject().put("existingRecords", 10_000).put("importedRows", 10_000).put("recordsAfter", 20_000)
            .put("parseMs", ms(started, parsed)).put("previewAndClassificationMs", ms(parsed, planned))
            .put("atomicCommitMs", ms(planned, committed)).put("verificationMs", ms(committed, verified))
            .put("totalToVerifiedMs", ms(started, verified)).put("sampledPssKb", Debug.getPss())
        instrumentation.sendStatus(2, Bundle().apply { putString("financegenie_csv_performance", report.toString()) })
    }
}
