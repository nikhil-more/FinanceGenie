package com.financegenie.benchmarks

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class OfflineStartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @OptIn(androidx.benchmark.macro.ExperimentalMacrobenchmarkApi::class)
    @Test fun coldOverviewWithTenThousandRecords() {
        val target = "com.financegenie.android.benchmark"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("am force-stop $target")
        val context = InstrumentationRegistry.getInstrumentation().context
        context.startActivity(Intent().setComponent(ComponentName(target, "com.financegenie.android.BenchmarkSetupActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Synthetic benchmark setup must complete", device.wait(Until.hasObject(By.text("FinanceGenie benchmark ready: 10000")), 120_000))
        val committedFrames = LinkedBlockingQueue<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra("benchmark_run_id")?.let { committedFrames.offer(it) }
            }
        }
        val filter = IntentFilter("com.financegenie.android.benchmark.OVERVIEW_DRAWN")
        val permission = "$target.permission.PREPARE_FIXTURE"
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter, permission, null)
        try {
            benchmark.measureRepeated(
                packageName = target,
                metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
                iterations = 20,
                // Preserve the seeded data and report the installed compilation state explicitly.
                compilationMode = CompilationMode.Ignore(),
                startupMode = StartupMode.COLD,
                setupBlock = { device.wakeUp(); pressHome() },
            ) {
                val runId = UUID.randomUUID().toString()
                startActivityAndWait { intent -> intent.putExtra("benchmark_run_id", runId) }
                assertEquals("This launch must report a committed loaded Overview frame", runId, committedFrames.poll(20, TimeUnit.SECONDS))
                assertTrue("Loaded November fixture must be visible", device.wait(Until.hasObject(By.pkg(target).res("activity_count").text("1652 matching transactions")), 20_000))
                assertTrue("Exact fixture spending must be drawn", device.wait(Until.hasObject(By.pkg(target).res("metric_spending").text("₹948.75")), 20_000))
                device.waitForIdle(5_000)
                assertEquals("Benchmark must remain in the foreground", target, device.currentPackageName)
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}

/** Direct process-cold baseline, independent of incomplete Perfetto metric extraction. */
class OfflineReadinessBenchmark {
    private data class CommittedFrame(val runId: String, val committedAtNanos: Long, val processId: Int)

    @Test fun coldLaunchToConfirmedOverviewWithTenThousandRecords() {
        val target = "com.financegenie.android.benchmark"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am force-stop $target")
        context.startActivity(Intent().setComponent(ComponentName(target, "com.financegenie.android.BenchmarkSetupActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Synthetic benchmark setup must complete", device.wait(Until.hasObject(By.text("FinanceGenie benchmark ready: 10000")), 120_000))
        val committedFrames = LinkedBlockingQueue<CommittedFrame>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val runId = intent?.getStringExtra("benchmark_run_id") ?: return
                committedFrames.offer(CommittedFrame(runId,
                    intent.getLongExtra("benchmark_committed_at_nanos", -1), intent.getIntExtra("benchmark_process_id", -1)))
            }
        }
        val filter = IntentFilter("com.financegenie.android.benchmark.OVERVIEW_DRAWN")
        val permission = "$target.permission.PREPARE_FIXTURE"
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter, permission, null)
        val confirmedTimes = mutableListOf<Double>()
        val committedTimes = mutableListOf<Double>()
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        // Loaded-frame + exact visible-value assertions provide readiness. Repeated
        // generic idle waits add automation latency after that frame is already ready.
        configurator.waitForIdleTimeout = 0
        try {
            repeat(20) {
                device.executeShellCommand("am force-stop $target")
                assertTrue("Process must be absent before each timed launch", device.executeShellCommand("pidof $target").trim().isEmpty())
                device.wakeUp()
                device.pressHome()
                assertTrue("Previous app window must be gone", device.wait(Until.gone(By.pkg(target)), 5_000))
                committedFrames.clear()
                val runId = UUID.randomUUID().toString()
                val intent = Intent().setComponent(ComponentName(target, "com.financegenie.android.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("benchmark_run_id", runId)
                val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                context.startActivity(intent)
                val committed = checkNotNull(committedFrames.poll(30, TimeUnit.SECONDS)) {
                    "This launch did not report a committed loaded Overview frame"
                }
                assertEquals("Frame signal must belong to this launch", runId, committed.runId)
                assertTrue("Frame timestamp must follow launch and precede receipt",
                    committed.committedAtNanos in startedAtNanos..SystemClock.elapsedRealtimeNanos())
                assertTrue("Frame signal must identify the launched process", committed.processId > 0 &&
                    device.executeShellCommand("pidof $target").trim().split(Regex("\\s+")).contains(committed.processId.toString()))
                assertTrue("Loaded November fixture must be visible", device.wait(Until.hasObject(By.pkg(target).res("activity_count").text("1652 matching transactions")), 20_000))
                assertTrue("Exact fixture spending must be drawn", device.wait(Until.hasObject(By.pkg(target).res("metric_spending").text("₹948.75")), 20_000))
                assertEquals("Benchmark must remain in the foreground", target, device.currentPackageName)
                val confirmedAtNanos = SystemClock.elapsedRealtimeNanos()
                committedTimes += (committed.committedAtNanos - startedAtNanos) / 1_000_000.0
                confirmedTimes += (confirmedAtNanos - startedAtNanos) / 1_000_000.0
            }
        } finally {
            configurator.waitForIdleTimeout = previousIdleTimeout
            context.unregisterReceiver(receiver)
        }
        val result = JSONObject().put("records", 10_000).put("operations", 20).put("processColdVerified", true)
            .put("uiAutomatorIdleTimeoutMs", 0)
            .put("method", "Explicit Activity launch to verified committed Overview; warm OS/SQLite caches; installed compilation state")
            .put("launchToConfirmedOverviewMs", stats(confirmedTimes))
            .put("launchToCommittedFrameMs", stats(committedTimes))
        instrumentation.sendStatus(2, Bundle().apply { putString("financegenie_startup", result.toString()) })
    }

    private fun stats(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        return JSONObject().put("runs", JSONArray(values)).put("p50", sorted[ceil(sorted.size * 0.50).toInt() - 1])
            .put("p95", sorted[ceil(sorted.size * 0.95).toInt() - 1]).put("max", sorted.last())
    }
}
