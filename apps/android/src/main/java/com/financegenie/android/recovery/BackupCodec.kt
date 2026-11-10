package com.financegenie.android.recovery

import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.*
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.*
import java.util.UUID

/** Strict, fixed-depth streaming schema. Unknown/duplicate fields are errors. */
object BackupCodec {
    const val MAX_BYTES = 50L * 1024 * 1024
    val limits = linkedMapOf("accounts" to 1_000, "categories" to 10_000, "transactions" to 100_000,
        "budgets" to 100_000, "observations" to 100_000, "evidence" to 200_000,
        "importBatches" to 10_000, "importReceipts" to 200_000)

    fun write(backup: PortableBackup, output: OutputStream, clock: Clock) {
        backup.validate(clock)
        val writer = jsonWriter(LimitedOutput(output))
        writer.beginObject().name("format").value("FinanceGenieBackup").name("schemaVersion").value(1)
        writer.name("createdAtMillis").value(backup.createdAtMillis.toString())
        writer.name("counts"); writer.row(*backup.counts().map { it.key to it.value.toString() }.toTypedArray())
        writer.name("ledger"); payload(writer, backup.ordered())
        writer.name("sha256").value(checksum(backup)).endObject()
        writer.flush() // Caller closes the provider stream and handles close-time failures.
    }

    fun read(input: InputStream, clock: Clock): PortableBackup {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = JsonReader(InputStreamReader(LimitedInput(input), decoder)).apply { strictness = Strictness.STRICT }
        var created: Long? = null
        var counts: Map<String, Int>? = null
        var backup: PortableBackup? = null
        var digest: String? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name))
            when (name) {
                "format" -> require(reader.string() == "FinanceGenieBackup")
                "schemaVersion" -> { require(reader.peek() == JsonToken.NUMBER); require(reader.nextString() == "1") }
                "createdAtMillis" -> created = canonicalLong(reader.string())
                "counts" -> counts = reader.fields().let { f -> limits.keys.associateWith { f.long(it).also { n -> require(n in 0..limits.getValue(it).toLong()) }.toInt() }.also { f.done(Unit) } }
                "ledger" -> backup = readPayload(reader)
                "sha256" -> digest = reader.string().also { require(Regex("[a-f0-9]{64}").matches(it)) }
                else -> error("Unsupported backup field")
            }
        }
        reader.endObject()
        require(seen == setOf("format", "schemaVersion", "createdAtMillis", "counts", "ledger", "sha256"))
        require(reader.peek() == JsonToken.END_DOCUMENT)
        val result = requireNotNull(backup).copy(createdAtMillis = requireNotNull(created))
        require(result.counts() == counts && checksum(result) == digest) { "Backup integrity check failed" }
        result.validate(clock)
        return result
    }

    private fun checksum(backup: PortableBackup): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }
        val writer = jsonWriter(DigestOutputStream(sink, digest))
        writer.beginObject().name("createdAtMillis").value(backup.createdAtMillis.toString()).name("ledger")
        payload(writer, backup.ordered()); writer.endObject(); writer.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun payload(w: JsonWriter, backup: PortableBackup) {
        val l = backup.ledger
        w.beginObject().name("settings"); w.row("localId" to l.settings.localId, "currency" to l.settings.currency,
            "reportingZone" to l.settings.reportingZone, "revision" to l.settings.revision)
        fun <T> table(name: String, rows: List<T>, write: (T) -> Unit) { w.name(name).beginArray(); rows.forEach { checkInterrupted(); write(it) }; w.endArray() }
        table("accounts", l.accounts) { (v, m) -> w.row("id" to v.id, "name" to v.name, "kind" to v.kind, "openingDate" to v.openingDate,
            "openingBalanceMinor" to v.openingBalanceMinor, "currency" to v.currency, "isArchived" to v.isArchived, *meta(m)) }
        table("categories", l.categories) { (v, m) -> w.row("id" to v.id, "name" to v.name, "isArchived" to v.isArchived, *meta(m)) }
        table("transactions", l.transactions) { (v, d, m) -> w.row("id" to v.id, "kind" to v.kind, "amountMinor" to v.amountMinor,
            "accountId" to v.accountId, "effectiveDate" to v.effectiveDate, "destinationAccountId" to v.destinationAccountId,
            "categoryId" to v.categoryId, "originalExpenseId" to v.originalExpenseId, "unlinkedRefundConfirmed" to v.unlinkedRefundConfirmed,
            "adjustmentDirection" to v.adjustmentDirection, "adjustmentReason" to v.adjustmentReason, "occurredAt" to v.occurredAt,
            "originalZoneId" to v.originalZoneId, "isDeleted" to v.isDeleted, "merchant" to d.merchant, "notes" to d.notes, "source" to d.source, *meta(m)) }
        table("budgets", l.budgets) { (v, m) -> w.row("id" to v.id, "month" to v.month, "limitMinor" to v.limitMinor, "categoryId" to v.categoryId, *meta(m)) }
        table("observations", l.observations) { (v, m) -> w.row("id" to v.id, "accountId" to v.accountId, "asOfDate" to v.asOfDate, "reportedBalanceMinor" to v.reportedBalanceMinor, *meta(m)) }
        table("evidence", l.evidence) { (v, group, at, active) -> w.row("id" to v.id, "transactionId" to v.transactionId, "source" to v.source,
            "sourceKey" to v.sourceKey, "importBatchId" to v.importBatchId, "groupId" to group, "recordedAtMillis" to at, "isActive" to active) }
        table("importBatches", l.importBatches) { v -> w.row("id" to v.id, "accountId" to v.accountId, "fingerprint" to v.fingerprint,
            "acceptedCount" to v.acceptedCount, "isUndone" to v.isUndone, "createdAtMillis" to v.createdAtMillis) }
        table("importReceipts", backup.receipts) { v -> w.row("batchId" to v.batchId, "transactionId" to v.transactionId, "afterRevision" to v.afterRevision, "created" to v.created) }
        w.endObject()
    }

    private fun readPayload(r: JsonReader): PortableBackup {
        var settings: LedgerSettings? = null
        var accounts: List<StoredRecord<LedgerAccount>>? = null
        var categories: List<StoredRecord<LedgerCategory>>? = null
        var events: List<TransactionRecord>? = null
        var budgets: List<StoredRecord<MonthlyBudget>>? = null
        var observations: List<StoredRecord<BalanceObservation>>? = null
        var evidence: List<SourceEvidenceRecord>? = null
        var batches: List<ImportBatchRecord>? = null
        var receipts: List<ImportReceipt>? = null
        val seen = mutableSetOf<String>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName(); require(seen.add(name))
            when (name) {
                "settings" -> settings = r.fields().let { f -> f.done(LedgerSettings(f.uuid("localId"), f.s("currency"), f.s("reportingZone"), f.long("revision"))) }
                "accounts" -> accounts = r.table(name) { f -> f.done(StoredRecord(LedgerAccount(f.uuid("id"), f.s("name"), AccountKind.valueOf(f.s("kind")), LocalDate.parse(f.s("openingDate")), f.long("openingBalanceMinor"), f.s("currency"), f.bool("isArchived")), f.metadata())) }
                "categories" -> categories = r.table(name) { f -> f.done(StoredRecord(LedgerCategory(f.s("id"), f.s("name"), f.bool("isArchived")), f.metadata())) }
                "transactions" -> events = r.table(name) { f -> f.done(TransactionRecord(LedgerTransaction(f.uuid("id"), TransactionKind.valueOf(f.s("kind")),
                    f.long("amountMinor"), f.uuid("accountId"), LocalDate.parse(f.s("effectiveDate")), f.uuidOrNull("destinationAccountId"),
                    f.optional("categoryId"), f.uuidOrNull("originalExpenseId"), f.bool("unlinkedRefundConfirmed"), f.optional("adjustmentDirection")?.let(AdjustmentDirection::valueOf),
                    f.optional("adjustmentReason"), f.optional("occurredAt")?.let(Instant::parse), f.optional("originalZoneId"), f.bool("isDeleted")),
                    TransactionDetails(f.s("merchant"), f.s("notes"), TransactionSource.valueOf(f.s("source"))), f.metadata())) }
                "budgets" -> budgets = r.table(name) { f -> f.done(StoredRecord(MonthlyBudget(f.uuid("id"), YearMonth.parse(f.s("month")), f.long("limitMinor"), f.optional("categoryId")), f.metadata())) }
                "observations" -> observations = r.table(name) { f -> f.done(StoredRecord(BalanceObservation(f.uuid("id"), f.uuid("accountId"), LocalDate.parse(f.s("asOfDate")), f.long("reportedBalanceMinor")), f.metadata())) }
                "evidence" -> evidence = r.table(name) { f -> f.done(SourceEvidenceRecord(SourceEvidenceInput(f.uuid("id"), f.uuid("transactionId"), TransactionSource.valueOf(f.s("source")),
                    f.s("sourceKey"), f.uuidOrNull("importBatchId")), f.uuid("groupId"), f.long("recordedAtMillis"), f.bool("isActive"))) }
                "importBatches" -> batches = r.table(name) { f -> f.done(ImportBatchRecord(f.uuid("id"), f.uuid("accountId"), f.s("fingerprint"), f.long("acceptedCount").also { require(it in 1L..10_000L) }.toInt(), f.bool("isUndone"), f.long("createdAtMillis"))) }
                "importReceipts" -> receipts = r.table(name) { f -> f.done(ImportReceipt(f.uuid("batchId"), f.uuid("transactionId"), f.long("afterRevision"), f.bool("created"))) }
                else -> error("Unsupported backup table")
            }
        }
        r.endObject(); require(seen == limits.keys + "settings")
        return PortableBackup(StoredLedger(requireNotNull(settings), requireNotNull(accounts), requireNotNull(categories), requireNotNull(events),
            requireNotNull(budgets), requireNotNull(observations), requireNotNull(evidence), requireNotNull(batches)), requireNotNull(receipts), 0)
    }

    private fun JsonWriter.row(vararg fields: Pair<String, Any?>) {
        beginObject(); fields.forEach { (name, v) -> name(name); when (v) { null -> nullValue(); is Boolean -> value(v); else -> value(v.toString()) } }; endObject()
    }
    private fun meta(m: RecordMetadata): Array<Pair<String, Any?>> = arrayOf("revision" to m.revision, "createdAtMillis" to m.createdAtMillis, "updatedAtMillis" to m.updatedAtMillis)
    private fun canonicalLong(s: String): Long = s.toLong().also { require(it.toString() == s) }
    private fun jsonWriter(output: OutputStream) = JsonWriter(OutputStreamWriter(output,
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))).apply { serializeNulls = true }
    private fun JsonReader.string(): String {
        require(peek() == JsonToken.STRING)
        return nextString().also { value ->
            require(value.length <= 2_000)
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (Character.isHighSurrogate(c)) require(i < value.length && Character.isLowSurrogate(value[i++]))
                else require(!Character.isLowSurrogate(c))
            }
        }
    }
    private fun JsonReader.fields(): Fields {
        val values = linkedMapOf<String, Any?>(); beginObject()
        while (hasNext()) {
            val name = nextName(); require(name.length <= 64 && name !in values && values.size < 32)
            values[name] = when (peek()) { JsonToken.STRING -> string(); JsonToken.BOOLEAN -> nextBoolean(); JsonToken.NULL -> { nextNull(); null }; else -> error("Invalid backup field type") }
        }
        endObject(); return Fields(values)
    }
    private fun <T> JsonReader.table(name: String, convert: (Fields) -> T): List<T> {
        val rows = mutableListOf<T>(); beginArray()
        while (hasNext()) { checkInterrupted(); require(rows.size < limits.getValue(name)); rows += convert(fields()) }
        endArray(); return rows
    }
    private class Fields(val values: Map<String, Any?>) {
        val used = mutableSetOf<String>()
        fun get(name: String): Any? { require(name in values); used += name; return values[name] }
        fun s(name: String): String = get(name) as? String ?: error("Expected string")
        fun optional(name: String): String? = get(name).let { if (it == null) null else it as? String ?: error("Expected nullable string") }
        fun bool(name: String): Boolean = get(name) as? Boolean ?: error("Expected boolean")
        fun long(name: String) = canonicalLong(s(name))
        fun uuid(name: String) = UUID.fromString(s(name)).also { require(it.toString() == values[name]) }
        fun uuidOrNull(name: String) = optional(name)?.let { UUID.fromString(it).also { id -> require(id.toString() == it) } }
        fun metadata() = RecordMetadata(long("revision"), long("createdAtMillis"), long("updatedAtMillis"))
        fun <T> done(value: T): T { require(used == values.keys); return value }
    }
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException() }
    private class LimitedInput(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int { checkInterrupted(); return super.read().also { if (it >= 0) { count++; require(count <= MAX_BYTES) } } }
        override fun read(b: ByteArray, off: Int, len: Int): Int { checkInterrupted(); return `in`.read(b, off, minOf(len.toLong(), MAX_BYTES + 1 - count).toInt()).also { if (it > 0) { count += it; require(count <= MAX_BYTES) } } }
    }
    private class LimitedOutput(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
        override fun write(b: Int) { count++; require(count <= MAX_BYTES); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len; require(count <= MAX_BYTES); out.write(b, off, len) }
    }
}
