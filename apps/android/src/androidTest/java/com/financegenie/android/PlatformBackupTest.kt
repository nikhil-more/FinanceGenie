package com.financegenie.android

import android.app.backup.*
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Framework extraction only; never changes phone backup/transport/security settings. */
class PlatformBackupTest {
    @Test fun configuredCloudAndDeviceTransferRulesExcludeEveryPrivateCanary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Use the isolated acceptance driver", context.packageName.endsWith(".acceptance") || context.packageName.endsWith(".backupcontrol"))
        val positive = context.packageName.endsWith(".backupcontrol")
        assertEquals(positive, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        val systemEligible = if (Build.VERSION.SDK_INT == 26) {
            // Android 8 otherwise leaves a newly installed positive control stopped.
            val activity = if (positive) instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else null
            try {
                legacySystemEligibility(context).also { assertEquals("Real BackupManager eligibility", positive, it) }
            } finally { activity?.let { instrumentation.runOnMainSync { it.finish() } } }
        } else null
        var suppliedLegacyRules = false
        val extractionContext = if (!positive && Build.VERSION.SDK_INT < 31) {
            val field = ApplicationInfo::class.java.getField("fullBackupContent")
            if (field.getInt(context.applicationInfo) == 0) {
                // Legacy PackageParser ignores fullBackupContent when allowBackup=false.
                // The real service rejects this app (checked above on API 26). To also
                // exercise its declared XML, supply the omitted resource on a COPY for
                // this forced-agent test only; never mutate installed metadata/flags.
                val copy = ApplicationInfo(context.applicationInfo)
                field.setInt(copy, R.xml.backup_rules)
                suppliedLegacyRules = true
                object : ContextWrapper(context) { override fun getApplicationInfo() = copy }
            } else context
        } else context
        val ce = context
        val de = context.createDeviceProtectedStorageContext()
        fun seed(scope: Context, name: String) {
            File(scope.filesDir, "acceptance-canary-only.txt").writeText("FG_CANARY_ONLY_FILES_$name")
            assertTrue(scope.getSharedPreferences("acceptance-canary-only", Context.MODE_PRIVATE).edit().putString("canary", "FG_CANARY_ONLY_PREFS_$name").commit())
            scope.openOrCreateDatabase("acceptance-canary-only.db", Context.MODE_PRIVATE, null).use {
                it.execSQL("CREATE TABLE IF NOT EXISTS canary (value TEXT NOT NULL)")
                it.execSQL("INSERT INTO canary VALUES (?)", arrayOf("FG_CANARY_ONLY_DATABASE_$name"))
            }
        }
        seed(ce, "CE"); seed(de, "DE")
        val result = JSONArray()
        try {
            for (destination in if (Build.VERSION.SDK_INT >= 31) listOf(0, 1) else listOf(0)) {
                val agent = object : BackupAgent() {
                    init { attachBaseContext(extractionContext) }
                    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
                    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
                }
                if (Build.VERSION.SDK_INT >= 31) BackupAgent::class.java.getMethod("onCreate", UserHandle::class.java, Int::class.javaPrimitiveType)
                    .invoke(agent, Process.myUserHandle(), destination)
                else agent.onCreate()
                val pipe = ParcelFileDescriptor.createPipe()
                val executor = Executors.newSingleThreadExecutor()
                val reading = executor.submit<ByteArray> {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; check(bytes.size() + n <= 16 * 1024 * 1024); bytes.write(buffer, 0, n) }
                        bytes.toByteArray()
                    }
                }
                val bytes = try {
                    pipe[1].use { fd ->
                        // Construction is hidden SDK plumbing. The host grants only this test
                        // process --no-hidden-api-checks; production never uses reflection here.
                        val output = if (Build.VERSION.SDK_INT >= 28) FullBackupDataOutput::class.java
                            .getConstructor(ParcelFileDescriptor::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .newInstance(fd, 16L * 1024 * 1024, if (destination == 1) 2 else 0)
                        else FullBackupDataOutput::class.java.getConstructor(ParcelFileDescriptor::class.java, Long::class.javaPrimitiveType)
                            .newInstance(fd, 16L * 1024 * 1024)
                        agent.onFullBackup(output)
                    }
                    reading.get(30, TimeUnit.SECONDS)
                } finally { executor.shutdownNow(); pipe.forEach { runCatching { it.close() } }; agent.onDestroy() }
                val text = bytes.toString(Charsets.ISO_8859_1)
                if (positive) {
                    listOf("FILES", "PREFS", "DATABASE").forEach { domain -> listOf("CE", "DE").forEach { storage ->
                        assertTrue("Positive control must really extract $domain/$storage", text.contains("FG_CANARY_ONLY_${domain}_$storage"))
                    } }
                } else assertEquals("Configured rules must extract no app data", 0, bytes.size)
                result.put(JSONObject().put("destination", if (destination == 1) "device-transfer" else "cloud")
                    .put("bytes", bytes.size).put("positiveControl", positive).put("privateCanariesPresent", positive)
                    .put("legacyRulesMetadataSupplied", suppliedLegacyRules)
                    .put("systemBackupEligible", systemEligible ?: JSONObject.NULL))
            }
        } finally {
            for (scope in listOf(ce, de)) {
                File(scope.filesDir, "acceptance-canary-only.txt").delete()
                scope.deleteDatabase("acceptance-canary-only.db")
                scope.getSharedPreferences("acceptance-canary-only", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("financegenie_backup_extraction", result.toString()) })
    }

    /** Read-only query as shell; no backup setting, transport or permission changes. */
    private fun legacySystemEligibility(context: Context): Boolean {
        check(Build.VERSION.SDK_INT == 26)
        require(context.packageName.matches(Regex("com\\.financegenie\\.android\\.(acceptance|backupcontrol)")))
        val stub = Class.forName("android.app.backup.IBackupManager\$Stub")
        val transaction = stub.getDeclaredField("TRANSACTION_isAppEligibleForBackup").apply { isAccessible = true }.getInt(null)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val reply = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "service call backup $transaction s16 ${context.packageName}")).use { it.readBytes().toString(Charsets.UTF_8) }
        val match = Regex("""Parcel\(\s*(?:0x[0-9a-fA-F]+:\s*)?00000000\s+(00000000|00000001)\b""").find(reply)
        assertNotNull("BackupManager eligibility must return a boolean: ${reply.take(200)}", match)
        return requireNotNull(match).groupValues[1] == "00000001"
    }
}
