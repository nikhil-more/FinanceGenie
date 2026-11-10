package com.financegenie.android.csv

import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.Money
import com.financegenie.android.domain.UNCATEGORIZED_ID
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

enum class CsvDirection { DEBIT, CREDIT }
enum class CsvAmountMode { AMOUNT_DIRECTION, DEBIT_CREDIT_COLUMNS }
enum class CsvDateFormat(val pattern: String) { ISO("uuuu-MM-dd"), DAY_FIRST("dd/MM/uuuu"), MONTH_FIRST("MM/dd/uuuu") }
enum class CsvRowProblem { COLUMN_COUNT, DATE, AMOUNT, DIRECTION, FUTURE_DATE, BEFORE_OPENING, TEXT_LENGTH }

data class CsvMapping(
    val date: Int = -1, val amount: Int = -1, val direction: Int = -1,
    val debit: Int = -1, val credit: Int = -1, val merchant: Int = -1,
    val category: Int = -1, val reference: Int = -1,
    val mode: CsvAmountMode = CsvAmountMode.AMOUNT_DIRECTION,
    val dateFormat: CsvDateFormat = CsvDateFormat.ISO,
) {
    fun validate(columns: Int) {
        val required = listOf(date) + if (mode == CsvAmountMode.AMOUNT_DIRECTION) listOf(amount, direction) else listOf(debit, credit)
        val selected = required + listOf(merchant, category, reference).filter { it >= 0 }
        if (required.any { it !in 0 until columns } || selected.any { it !in 0 until columns } || selected.distinct().size != selected.size) {
            throw CsvFailure(CsvProblem.INVALID_MAPPING)
        }
    }
    fun canonical(): String = listOf("1", date, amount, direction, debit, credit, merchant, category, reference, mode.name, dateFormat.name).joinToString(":")
    companion object {
        fun forHeaders(headers: List<String>): CsvMapping {
            fun column(name: String) = headers.indexOfFirst { it.equals(name, true) }
            return CsvMapping(column("date"), column("amount"), column("direction"), column("debit"), column("credit"),
                column("merchant"), column("category"), column("reference"))
        }
    }
}

data class CsvParsedRow(
    val number: Int, val eventId: UUID, val date: LocalDate? = null, val amountMinor: Long? = null,
    val direction: CsvDirection? = null, val merchant: String = "", val categoryHint: String = "",
    val categoryId: String? = null, val referenceHash: String? = null, val problem: CsvRowProblem? = null,
)

fun csvFileIdentity(profile: UUID, fileDigest: String): String = digestParts("financegenie-file-1", profile.toString(), fileDigest)
fun csvFingerprint(profile: UUID, account: UUID, document: CsvDocument, mapping: CsvMapping): String {
    val identity = csvFileIdentity(profile, document.digest)
    // Retain only hashes, including the components needed to re-scope replay keys
    // when a future backup restore creates a fresh profile identity.
    val mappingHash = digestParts("mapping-1", mapping.canonical())
    val fingerprint = digestParts(identity, account.toString(), mappingHash)
    return "csv1:${document.digest}:$mappingHash:$identity:$fingerprint"
}

fun parseCsvRows(document: CsvDocument, mapping: CsvMapping, account: LedgerAccount, categories: List<LedgerCategory>, today: LocalDate): List<CsvParsedRow> {
    mapping.validate(document.headers.size)
    val formatter = DateTimeFormatter.ofPattern(mapping.dateFormat.pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    return document.rows.map { raw ->
        fun invalid(problem: CsvRowProblem) = CsvParsedRow(raw.number, raw.eventId, problem = problem)
        if (raw.cells.size != document.headers.size) return@map invalid(CsvRowProblem.COLUMN_COUNT)
        fun cell(index: Int) = if (index < 0) "" else raw.cells[index].trim()
        val date = try { LocalDate.parse(cell(mapping.date), formatter).also { require(it.year in 1..9999) } }
            catch (_: IllegalArgumentException) { return@map invalid(CsvRowProblem.DATE) }
            catch (_: java.time.format.DateTimeParseException) { return@map invalid(CsvRowProblem.DATE) }
        if (date > today) return@map invalid(CsvRowProblem.FUTURE_DATE)
        if (date < account.openingDate) return@map invalid(CsvRowProblem.BEFORE_OPENING)
        val direction: CsvDirection
        val amount: Long
        if (mapping.mode == CsvAmountMode.AMOUNT_DIRECTION) {
            direction = CsvDirection.entries.firstOrNull { it.name == cell(mapping.direction).uppercase(Locale.ROOT) }
                ?: return@map invalid(CsvRowProblem.DIRECTION)
            amount = try { Money.parseAmount(cell(mapping.amount)) } catch (_: IllegalArgumentException) { return@map invalid(CsvRowProblem.AMOUNT) }
        } else {
            fun amountAt(index: Int): Long = cell(index).let { if (it.isEmpty()) 0 else Money.parseAmount(it, allowZero = true) }
            val debit: Long
            val credit: Long
            try { debit = amountAt(mapping.debit); credit = amountAt(mapping.credit) }
            catch (_: IllegalArgumentException) { return@map invalid(CsvRowProblem.AMOUNT) }
            if ((debit > 0) == (credit > 0)) return@map invalid(CsvRowProblem.AMOUNT)
            direction = if (debit > 0) CsvDirection.DEBIT else CsvDirection.CREDIT
            amount = maxOf(debit, credit)
        }
        val merchant = cell(mapping.merchant)
        val hint = cell(mapping.category)
        val reference = cell(mapping.reference)
        if (merchant.length > 200 || hint.length > 80 || reference.length > 256) return@map invalid(CsvRowProblem.TEXT_LENGTH)
        val active = categories.filterNot { it.isArchived }
        val category = if (hint.isEmpty()) UNCATEGORIZED_ID else active.firstOrNull { it.id == hint }?.id
            ?: active.filter { it.id.equals(hint, true) || it.name.equals(hint, true) }.singleOrNull()?.id
        CsvParsedRow(raw.number, raw.eventId, date, amount, direction, merchant, hint, category,
            reference.takeIf { it.isNotEmpty() }?.let { digestParts("reference-1", account.id.toString(), it) })
    }
}
