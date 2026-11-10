package com.financegenie.android.recovery

import android.content.Context
import android.util.AtomicFile
import com.financegenie.android.data.LedgerSettings
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.RoomLedgerRepository
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProfileSession(val databaseName: String, val repository: RoomLedgerRepository, val cleanupPending: Boolean = false)

/** One active personal ledger; the namespace also isolates physical-device tests. */
class LocalProfileStore(
    private val context: Context,
    private val clock: Clock,
    private val namespace: String = "financegenie",
    private val openDatabase: (String) -> FinanceGenieDatabase = { FinanceGenieDatabase.open(context, it, clock) },
) {
    private val mutex = Mutex()
    private val legacy = "$namespace-ledger.db"
    private val prefix = "$namespace-profile-"
    private val pointer = AtomicFile(File(context.filesDir, "$namespace-active-profile"))
    private var database: FinanceGenieDatabase
    private val mutable: MutableStateFlow<ProfileSession>
    val session get() = mutable.asStateFlow()

    init {
        require(Regex("[a-zA-Z0-9-]{1,80}").matches(namespace))
        val name = if (pointer.baseFile.exists() || File(pointer.baseFile.path + ".bak").exists()) {
            val bytes = pointer.readFully()
            require(bytes.size <= 160)
            bytes.toString(Charsets.UTF_8).also { require(owns(it)) }
        } else legacy
        // A missing selected DB is a recovery error, never an empty-ledger fallback.
        if (name != legacy) check(context.getDatabasePath(name).isFile) { "Active local ledger is missing" }
        database = openDatabase(name)
        mutable = MutableStateFlow(ProfileSession(name, RoomLedgerRepository(database, clock), !cleanupComplete(name)))
    }

    suspend fun backup(): PortableBackup = mutex.withLock { session.value.repository.portableBackup() }

    suspend fun restore(backup: PortableBackup, expected: LedgerSettings) = replace(expected, backup)
    suspend fun deleteAll(expected: LedgerSettings) = replace(expected, null)

    private suspend fun replace(expected: LedgerSettings, source: PortableBackup?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            source?.validate(clock)
            val name = "$prefix${UUID.randomUUID()}.db"
            val staged = openDatabase(name)
            var activated = false
            try {
                val repo = RoomLedgerRepository(staged, clock)
                if (source != null) {
                    val replacement = source.forNewProfile(UUID.randomUUID())
                    repo.populate(replacement)
                    // Re-read all tables/receipts after commit before activation.
                    val actual = repo.portableBackup()
                    require(actual.copy(createdAtMillis = replacement.createdAtMillis).ordered() == replacement.ordered())
                } else repo.read()
                ensureActive()
                // Do not cancel between publishing the pointer, closing handles and cleanup.
                withContext(NonCancellable) {
                    val oldDatabase = database
                    try {
                        session.value.repository.retire(expected) {
                            writePointer(name)
                            database = staged
                            activated = true
                            mutable.value = ProfileSession(name, repo)
                        }
                    } finally {
                        // Once the pointer is published, never delete the selected replacement,
                        // even if ending the old read transaction reports an I/O failure.
                        if (activated) {
                            var closed = true
                            try { oldDatabase.close() } catch (_: Exception) { closed = false }
                            mutable.value = mutable.value.copy(cleanupPending = !closed || !cleanupComplete(name))
                        }
                    }
                }
            } finally {
                if (!activated) {
                    staged.close()
                    // Preserve the staged DB on an indeterminate pointer-read failure.
                    if (runCatching { pointer.readFully().toString(Charsets.UTF_8) != name }.getOrDefault(false) ||
                        !pointer.baseFile.exists()) context.deleteDatabase(name)
                }
            }
        }
    }

    suspend fun retryCleanup() = mutex.withLock { withContext(Dispatchers.IO) {
        mutable.value = mutable.value.copy(cleanupPending = !cleanupComplete(mutable.value.databaseName))
    } }

    fun close() { database.close() }

    private fun owns(name: String): Boolean = name == legacy ||
        (name.startsWith(prefix) && Regex("${Regex.escape(prefix)}[a-f0-9-]{36}\\.db").matches(name))

    private fun cleanUnused(active: String): Boolean {
        fun bases() = context.databaseList().map { it.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal") }
            .filter { owns(it) && it != active }.distinct()
        bases().forEach { name ->
            context.deleteDatabase(name)
            listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
        }
        return bases().isEmpty()
    }

    private fun cleanupComplete(active: String): Boolean = try { cleanUnused(active) } catch (_: Exception) { false }

    private fun writePointer(name: String) {
        val stream = pointer.startWrite()
        try {
            stream.write(name.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            pointer.finishWrite(stream)
        } catch (e: Exception) { pointer.failWrite(stream); throw e }
        if (pointer.readFully().toString(Charsets.UTF_8) != name) throw IOException("Active profile was not saved")
    }
}
