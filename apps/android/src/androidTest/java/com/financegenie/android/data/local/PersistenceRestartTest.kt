package com.financegenie.android.data.local

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.LedgerCalculator
import com.financegenie.android.domain.LedgerCategory
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Can run normally, or as two separate instrumentation invocations around a force-stop. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PersistenceRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)

    @Test
    fun a_seedPersistentLedger() = runBlocking<Unit> {
        // Delete only this test's own database, never the application's ledger or data directory.
        context.deleteDatabase(PROBE_DATABASE)
        val database = FinanceGenieDatabase.open(context, PROBE_DATABASE, storageClock)
        try {
            val repository = RoomLedgerRepository(database, storageClock)
            repository.saveAccount(storageAccounts()[0])
            repository.saveCategory(LedgerCategory("food", "Food"))
            repository.applyTransactions(listOf(TransactionWrite(storageExpense(amount = 1), TransactionDetails("Synthetic restart fixture"))))
            val ledger = repository.read()
            assertTrue(preferences.edit()
                .putInt("seed_pid", Process.myPid())
                .putString("local_id", ledger.settings.localId.toString())
                .putLong("revision", ledger.settings.revision)
                .commit())
            assertEquals(1, ledger.transactions.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun b_verifyPersistentLedgerAndCleanUp() = runBlocking<Unit> {
        val requireFreshProcess = InstrumentationRegistry.getArguments().getString("requireFreshProcess") == "true"
        val seedPid = preferences.getInt("seed_pid", -1)
        assertTrue("Seed stage must run first", seedPid > 0)
        if (requireFreshProcess) assertNotEquals("Verification must run in a different app process", seedPid, Process.myPid())
        val database = FinanceGenieDatabase.open(context, PROBE_DATABASE, storageClock)
        try {
            val repository = RoomLedgerRepository(database, storageClock)
            val ledger = repository.read()
            assertEquals(preferences.getString("local_id", null), ledger.settings.localId.toString())
            assertEquals(preferences.getLong("revision", -1), ledger.settings.revision)
            assertEquals(storageExpense(amount = 1), ledger.transactions.single().transaction)
            assertEquals("Synthetic restart fixture", ledger.transactions.single().details.merchant)
            assertEquals(1, ledger.evidence.size)
            assertEquals(1L, LedgerCalculator(storageClock).monthlyReport(ledger.snapshot, YearMonth.of(2026, 11)).netSpendingMinor)
            assertEquals(1, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
        }
        assertTrue(context.deleteDatabase(PROBE_DATABASE))
        assertTrue(preferences.edit().clear().commit())
    }

    companion object {
        private const val PROBE_DATABASE = "financegenie-test-restart-probe.db"
        private const val PROBE_PREFERENCES = "financegenie_test_restart_probe"
    }
}
