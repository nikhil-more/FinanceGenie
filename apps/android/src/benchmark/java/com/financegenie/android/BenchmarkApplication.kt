package com.financegenie.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.RoomLedgerRepository
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.AdjustmentDirection
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.MonthlyBudget
import com.financegenie.android.domain.TransactionKind
import com.financegenie.android.privacy.PrivacyPreferences
import com.financegenie.android.recovery.LocalProfileStore
import android.util.AtomicFile
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compiled only into the separate non-debuggable .benchmark application. */
class BenchmarkApplication : FinanceGenieApplication() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity) return
                val runId = activity.intent.getStringExtra("benchmark_run_id") ?: return
                check(!activity.fullyDrawnReporter.isFullyDrawnReported) { "Benchmark listener registered after reporting" }
                activity.fullyDrawnReporter.addOnReportDrawnListener {
                    // Keep capture alive until the loaded Overview's frame commits.
                    // Only the separate benchmark build contains this handshake.
                    val ready = Runnable {
                        val committedAtNanos = SystemClock.elapsedRealtimeNanos()
                        sendBroadcast(Intent("com.financegenie.android.benchmark.OVERVIEW_DRAWN")
                            .setPackage("com.financegenie.benchmarks")
                            .putExtra("benchmark_run_id", runId)
                            .putExtra("benchmark_committed_at_nanos", committedAtNanos)
                            .putExtra("benchmark_process_id", Process.myPid()), "$packageName.permission.PREPARE_FIXTURE")
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        val decor = activity.window.decorView
                        check(decor.isHardwareAccelerated) { "Frame measurement requires hardware rendering" }
                        decor.viewTreeObserver.registerFrameCommitCallback(ready)
                        decor.postInvalidateOnAnimation()
                    } else error("Frame measurement requires API 29")
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    val benchmarkClock: Clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC)
    private val databaseName = "performance-fixture-ledger.db"
    private val database = lazy { FinanceGenieDatabase.open(this, databaseName, benchmarkClock) }
    private val profiles = lazy { LocalProfileStore(this, benchmarkClock, "performance-fixture") { name ->
        if (name == databaseName) database.value else FinanceGenieDatabase.open(this, name, benchmarkClock)
    } }
    override val container: AppContainer by lazy {
        object : AppContainer {
            override val clock = benchmarkClock
            override val profiles get() = this@BenchmarkApplication.profiles.value
            override val repository get() = profiles.session.value.repository
            override val privacy = PrivacyPreferences(getSharedPreferences("benchmark-only", MODE_PRIVATE))
        }
    }

    // A fresh repository retains warm OS/SQLite caches but has no in-memory snapshot.
    fun freshRepository() = RoomLedgerRepository(database.value, benchmarkClock)

    suspend fun prepareFixture() = withContext(Dispatchers.IO) {
        check(packageName.endsWith(".benchmark") && !database.isInitialized() && !profiles.isInitialized())
        // This build has its own package/data sandbox, and this named file contains synthetic data only.
        databaseList().filter { it.startsWith("performance-fixture-") || it == "performance-fixture.db" }.forEach { deleteDatabase(it) }
        AtomicFile(File(filesDir, "performance-fixture-active-profile")).delete()
        val seedDatabase = FinanceGenieDatabase.open(this@BenchmarkApplication, databaseName, benchmarkClock)
        try {
            val repository = RoomLedgerRepository(seedDatabase, benchmarkClock)
            val accounts = listOf(AccountKind.BANK, AccountKind.CASH, AccountKind.CREDIT_CARD).mapIndexed { index, kind ->
                LedgerAccount(UUID(0, index + 1L), "Synthetic benchmark $kind", kind, LocalDate.of(2026, 1, 1))
            }
            accounts.forEach { repository.saveAccount(it) }
            repeat(20) { repository.saveCategory(LedgerCategory("benchmark_$it", "Synthetic benchmark category $it")) }
            val changes = (0 until 10_000).map { index ->
                val kind = when (index % 10) {
                    0 -> TransactionKind.INCOME
                    1 -> TransactionKind.TRANSFER
                    2 -> TransactionKind.REFUND
                    3 -> TransactionKind.ADJUSTMENT
                    else -> TransactionKind.EXPENSE
                }
                val amount = when (kind) {
                    TransactionKind.INCOME -> 10_000L
                    TransactionKind.TRANSFER -> 5_000L
                    TransactionKind.REFUND -> 25L
                    TransactionKind.ADJUSTMENT -> 5L
                    else -> 100L
                }
                val account = accounts[index % accounts.size]
                val event = LedgerTransaction(
                    id = UUID(1, index.toLong()), kind = kind, amountMinor = amount, accountId = account.id,
                    effectiveDate = YearMonth.of(2026, 6).plusMonths((index / 28 % 6).toLong()).atDay(index % 28 + 1),
                    destinationAccountId = accounts[(index + 1) % accounts.size].id.takeIf { kind == TransactionKind.TRANSFER },
                    categoryId = "benchmark_${index % 20}".takeIf { kind == TransactionKind.EXPENSE || kind == TransactionKind.REFUND },
                    unlinkedRefundConfirmed = kind == TransactionKind.REFUND,
                    adjustmentDirection = AdjustmentDirection.INCREASE.takeIf { kind == TransactionKind.ADJUSTMENT },
                    adjustmentReason = "Synthetic benchmark correction".takeIf { kind == TransactionKind.ADJUSTMENT },
                )
                TransactionWrite(event, TransactionDetails("Synthetic benchmark merchant $index", "Synthetic fixture note"))
            }
            repository.applyTransactions(changes)
            repository.saveBudget(MonthlyBudget(UUID(2, 1), YearMonth.of(2026, 11), 500_000))
        } finally { seedDatabase.close() }
    }
}

class BenchmarkSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var message by mutableStateOf("Preparing synthetic benchmark fixture")
        setContent { Text(message) }
        lifecycleScope.launch {
            try {
                (application as BenchmarkApplication).prepareFixture()
                message = "FinanceGenie benchmark ready: 10000"
            } catch (_: Exception) {
                message = "Synthetic benchmark setup failed"
            }
        }
    }
}
