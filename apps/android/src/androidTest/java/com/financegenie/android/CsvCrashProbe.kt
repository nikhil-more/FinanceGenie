package com.financegenie.android

import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.csv.*
import com.financegenie.android.data.*
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.RoomLedgerRepository
import com.financegenie.android.data.local.storageAccounts
import com.financegenie.android.data.local.storageBankId
import com.financegenie.android.domain.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host-coordinated process kill; only csv-crash-probe-only.db is touched. */
class CsvCrashProbe {
    @Test fun exerciseRealInterruptedCommitAndFreshProcessRecovery() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("csvCrashPhase")
        assumeTrue("Use the CSV device driver for this coordinated probe", phase in listOf("write", "verify", "restore"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "csv-crash-probe-only.db"
        val pidFile = File(context.filesDir, "csv-crash-probe-only.pid")
        val armed = AtomicBoolean(false)
        val clock = uiClock
        if (phase == "write") {
            context.deleteDatabase(name)
            pidFile.writeText(Process.myPid().toString())
        } else assertNotEquals("Reader must be a fresh process", pidFile.readText().toInt(), Process.myPid())
        val factory = object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                fun gate(db: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by db {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = db.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun executeInsert(): Long {
                                val id = statement.executeInsert()
                                if (sql.contains("`transactions`") && armed.compareAndSet(true, false)) {
                                    // The batch and its first financial row are now inside a real,
                                    // uncommitted SQLite transaction. Host force-stops this process.
                                    instrumentation.sendStatus(2, Bundle().apply { putString("csv_inside_commit", "true") })
                                    CountDownLatch(1).await(60, TimeUnit.SECONDS)
                                    throw AssertionError("Host did not stop the interrupted-commit probe")
                                }
                                return id
                            }
                        }
                    }
                }
                return object : SupportSQLiteOpenHelper by helper {
                    override val writableDatabase get() = gate(helper.writableDatabase)
                    override val readableDatabase get() = gate(helper.readableDatabase)
                }
            }
        }
        val database = if (phase == "write") Room.databaseBuilder(context, FinanceGenieDatabase::class.java, name)
            .openHelperFactory(factory).addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO ledger_settings VALUES (1, ?, 'INR', 'Asia/Kolkata', 0)", arrayOf(UUID(7, 7).toString()))
                    db.execSQL("INSERT INTO categories VALUES ('uncategorized', 'Uncategorized', 0, 1, 0, 0)")
                }
            }).build() else FinanceGenieDatabase.open(context, name, clock)
        val repository = RoomLedgerRepository(database, clock)
        fun document(prefix: String, count: Int, date: String = "2026-11-02") = CsvReader.read(buildString {
            append("date,amount,direction,merchant,category,reference\n")
            repeat(count) { append("$date,0.01,DEBIT,$prefix $it,Food,$prefix-$it\n") }
        }.byteInputStream())
        suspend fun accepted(document: CsvDocument): CsvImportRequest {
            val ledger = repository.read()
            return CsvImportRequest(document, CsvMapping.forHeaders(document.headers), storageBankId,
                document.rows.associate { it.number to CsvDecision(CsvAction.CREATE, categoryId = "food") }, ledger.settings.revision)
        }
        val baseline = document("Synthetic baseline", 5)
        try {
            when (phase) {
                "write" -> {
                    storageAccounts().forEach { repository.saveAccount(it) }
                    repository.saveCategory(LedgerCategory("food", "Food"))
                    repository.commitCsv(accepted(baseline))
                    val pending = accepted(document("Synthetic interrupted", 10_000, "2026-11-03"))
                    armed.set(true)
                    repository.commitCsv(pending)
                    fail("Host must stop inside the pending commit")
                }
                "verify" -> {
                    val recovered = repository.read()
                    assertEquals(5, recovered.transactions.size)
                    assertEquals(1, recovered.importBatches.size)
                    assertEquals(5, recovered.evidence.size)
                    assertEquals(5L, recovered.settings.revision)
                    assertFalse(recovered.importBatches.single().isUndone)
                    assertEquals(5, recovered.importBatches.single().acceptedCount)
                    assertEquals(5L, LedgerCalculator(clock).monthlyReport(recovered.snapshot, java.time.YearMonth.of(2026, 11)).netSpendingMinor)
                    repository.undoCsv(recovered.importBatches.single().id, recovered.settings.revision)
                    assertTrue(repository.read().transactions.all { it.transaction.isDeleted })
                    pidFile.writeText(Process.myPid().toString())
                }
                "restore" -> {
                    val undone = repository.read()
                    assertTrue(undone.importBatches.single().isUndone)
                    assertEquals(5, undone.transactions.size)
                    assertTrue(undone.transactions.all { it.transaction.isDeleted })
                    val replay = try { repository.commitCsv(accepted(baseline)); null } catch (error: CsvReplayException) { error }
                    assertNotNull("Undone file replay must survive process death", replay)
                    repository.restoreCsv(undone.importBatches.single().id, undone.settings.revision)
                    assertTrue(repository.read().transactions.none { it.transaction.isDeleted })
                    assertFalse(repository.read().importBatches.single().isUndone)
                }
            }
        } finally {
            database.close()
            if (phase == "restore") { context.deleteDatabase(name); pidFile.delete() }
        }
    }
}
