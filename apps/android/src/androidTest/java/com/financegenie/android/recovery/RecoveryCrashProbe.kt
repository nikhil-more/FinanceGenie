package com.financegenie.android.recovery

import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.storageClock
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RecoveryCrashProbe {
    @Test fun interruptStagePublicationOrDeleteAndReadInFreshProcess() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val phase = InstrumentationRegistry.getArguments().getString("recoveryCrashPhase")
        val boundary = InstrumentationRegistry.getArguments().getString("recoveryBoundary")
        assumeTrue("Use the isolated host driver", base.packageName.endsWith(".recoverycheck") && phase in listOf("write", "read") && boundary in listOf("stage", "publish", "delete"))
        val namespace = "recovery-crash-$boundary-only"
        val pidFile = File(base.filesDir, "$namespace.pid")
        val backupFile = File(base.filesDir, "$namespace.json")
        val armed = AtomicBoolean(false)
        fun gate() {
            instrumentation.sendStatus(2, Bundle().apply { putString("recovery_boundary_reached", "true") })
            CountDownLatch(1).await(60, TimeUnit.SECONDS)
            throw AssertionError("Host must stop this synthetic probe at the verified boundary")
        }
        val context = object : ContextWrapper(base) {
            override fun deleteDatabase(name: String): Boolean {
                if (phase == "write" && boundary != "stage" && name == "$namespace-ledger.db" && armed.compareAndSet(true, false)) gate()
                return super.deleteDatabase(name)
            }
        }
        if (phase == "write") {
            context.databaseList().filter { it.startsWith(namespace) }.forEach { context.deleteDatabase(it) }
            AtomicFile(File(context.filesDir, "$namespace-active-profile")).delete()
            pidFile.writeText(Process.myPid().toString())
        } else assertNotEquals(pidFile.readText().toInt(), Process.myPid())
        val factory = object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                val original = FrameworkSQLiteOpenHelperFactory().create(configuration)
                fun wrap(db: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by db {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = db.compileStatement(sql)
                        return object : SupportSQLiteStatement by statement {
                            override fun executeInsert(): Long {
                                val id = statement.executeInsert()
                                if (boundary == "stage" && sql.contains("`transactions`") && armed.compareAndSet(true, false)) gate()
                                return id
                            }
                        }
                    }
                }
                return object : SupportSQLiteOpenHelper by original {
                    override val writableDatabase get() = wrap(original.writableDatabase)
                    override val readableDatabase get() = wrap(original.readableDatabase)
                }
            }
        }
        val profiles = if (phase == "write" && boundary == "stage") LocalProfileStore(context, storageClock, namespace) { name ->
            Room.databaseBuilder(context, FinanceGenieDatabase::class.java, name).openHelperFactory(factory).addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO ledger_settings VALUES (1, ?, 'INR', 'Asia/Kolkata', 0)", arrayOf(UUID.randomUUID().toString()))
                    db.execSQL("INSERT INTO categories VALUES ('uncategorized', 'Uncategorized', 0, 1, 0, 0)")
                }
            }).build()
        } else LocalProfileStore(context, storageClock, namespace)
        try {
            if (phase == "write") {
                seedRecovery(profiles.session.value.repository)
                val backup = profiles.backup()
                backupFile.outputStream().use { BackupCodec.write(backup, it, storageClock) }
                armed.set(true)
                if (boundary == "delete") profiles.deleteAll(backup.ledger.settings) else profiles.restore(backup, backup.ledger.settings)
                fail("Expected host interruption")
            } else {
                val source = backupFile.inputStream().use { BackupCodec.read(it, storageClock) }
                val actual = profiles.backup()
                when (boundary) {
                    "stage" -> assertEquals(source.ordered(), actual.copy(createdAtMillis = source.createdAtMillis).ordered())
                    "publish" -> assertEquals(source.forNewProfile(actual.ledger.settings.localId).ordered(), actual.copy(createdAtMillis = source.createdAtMillis).ordered())
                    "delete" -> assertTrue(actual.ledger.accounts.isEmpty() && actual.ledger.transactions.isEmpty() && actual.ledger.evidence.isEmpty())
                }
                assertFalse(profiles.session.value.cleanupPending)
                assertTrue(context.databaseList().filter { it.startsWith(namespace) }.all { it.startsWith(profiles.session.value.databaseName) })
            }
        } finally {
            profiles.close()
            if (phase == "read") {
                context.databaseList().filter { it.startsWith(namespace) }.forEach { context.deleteDatabase(it) }
                AtomicFile(File(context.filesDir, "$namespace-active-profile")).delete()
                pidFile.delete(); backupFile.delete()
            }
        }
    }
}
