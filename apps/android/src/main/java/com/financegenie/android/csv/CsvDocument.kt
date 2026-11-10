package com.financegenie.android.csv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class CsvFailure(val reason: CsvProblem) : IllegalArgumentException(reason.name)
enum class CsvProblem { TOO_LARGE, TOO_MANY_ROWS, TOO_MANY_COLUMNS, FIELD_TOO_LONG, INVALID_UTF8, MALFORMED_QUOTES, INVALID_HEADER, INVALID_MAPPING, INVALID_ROW }

data class CsvRawRow(val number: Int, val cells: List<String>, val eventId: UUID = UUID.randomUUID())
data class CsvDocument(val digest: String, val headers: List<String>, val rows: List<CsvRawRow>, val blankLines: Int)

/** No source bytes, path or provider URI escapes this bounded read. */
object CsvReader {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_ROWS = 10_000
    const val MAX_COLUMNS = 64
    const val MAX_FIELD = 4_000

    fun read(input: InputStream): CsvDocument {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_BYTES) throw CsvFailure(CsvProblem.TOO_LARGE)
            bytes.write(buffer, 0, count)
        }
        val raw = bytes.toByteArray()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) { throw CsvFailure(CsvProblem.INVALID_UTF8) }
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var recordStarted = false
        var blankLines = 0
        var recordCount = 0
        fun endField() {
            row += field.toString()
            field.setLength(0)
            closed = false
            if (row.size > MAX_COLUMNS) throw CsvFailure(CsvProblem.TOO_MANY_COLUMNS)
        }
        fun endRecord() {
            endField()
            recordCount++
            if (recordCount > MAX_ROWS + 1) throw CsvFailure(CsvProblem.TOO_MANY_ROWS)
            if (!recordStarted && row.singleOrNull().isNullOrEmpty()) blankLines++ else records += row.toList()
            row.clear()
            recordStarted = false
        }
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else field.append(char)
            } else when (char) {
                '"' -> {
                    if (field.isNotEmpty() || closed) throw CsvFailure(CsvProblem.MALFORMED_QUOTES)
                    quoted = true; recordStarted = true
                }
                ',' -> { recordStarted = true; endField() }
                '\r', '\n' -> {
                    if (char == '\r' && index < text.length && text[index] == '\n') index++
                    endRecord()
                }
                else -> {
                    if (closed) throw CsvFailure(CsvProblem.MALFORMED_QUOTES)
                    field.append(char); recordStarted = true
                }
            }
            if (field.length > MAX_FIELD) throw CsvFailure(CsvProblem.FIELD_TOO_LONG)
        }
        if (quoted) throw CsvFailure(CsvProblem.MALFORMED_QUOTES)
        if (recordStarted || field.isNotEmpty() || row.isNotEmpty() || closed) endRecord()
        val headers = records.firstOrNull()?.map { it.trim() } ?: throw CsvFailure(CsvProblem.INVALID_HEADER)
        if (headers.any { it.isBlank() || it.length > 100 } || headers.map { it.lowercase(Locale.ROOT) }.distinct().size != headers.size) {
            throw CsvFailure(CsvProblem.INVALID_HEADER)
        }
        return CsvDocument(sha256(raw), headers, records.drop(1).mapIndexed { i, cells -> CsvRawRow(i + 2, cells) }, blankLines)
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun digestParts(vararg parts: String): String = sha256(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
