package com.financegenie.android.csv

import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CsvImportTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC)
    private val bank = UUID(0, 1)
    private val cash = UUID(0, 2)
    private val metadata = RecordMetadata(1, 0, 0)
    private fun ledger(transactions: List<TransactionRecord> = emptyList(), evidence: List<SourceEvidenceRecord> = emptyList()) = StoredLedger(
        LedgerSettings(UUID(0, 9), "INR", "Asia/Kolkata", 0),
        listOf(StoredRecord(LedgerAccount(bank, "Synthetic Bank", AccountKind.BANK, LocalDate.of(2026, 1, 1)), metadata),
            StoredRecord(LedgerAccount(cash, "Synthetic Cash", AccountKind.CASH, LocalDate.of(2026, 1, 1)), metadata)),
        listOf(LedgerCategory.uncategorized(), LedgerCategory("food", "Food")).map { StoredRecord(it, metadata) },
        transactions, emptyList(), emptyList(), evidence)
    private fun read(text: String) = CsvReader.read(text.byteInputStream())
    private val header = "date,amount,direction,merchant,category,reference\n"
    private fun request(document: CsvDocument, decisions: Map<Int, CsvDecision> = emptyMap(), mapping: CsvMapping = CsvMapping.forHeaders(document.headers)) =
        CsvImportRequest(document, mapping, bank, decisions, 0)
    private fun failure(problem: CsvProblem, operation: () -> Unit) {
        try { operation(); fail("Expected $problem") } catch (error: CsvFailure) { assertEquals(problem, error.reason) }
    }

    @Test fun replayDigestUsesTheStandardSha256Encoding() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".toByteArray(Charsets.UTF_8)))
    }

    @Test fun quotedCommasNewlinesDoubledQuotesBomAndCrLfArePreserved() {
        val document = read("\uFEFF" + header.replace("\n", "\r\n") + "2026-11-02,1.25,DEBIT,\"Synthetic \"\"cafe\"\", shop\r\nnext line\",Food,REF\r\n\r\n")
        assertEquals(1, document.rows.size)
        assertEquals("Synthetic \"cafe\", shop\r\nnext line", document.rows.single().cells[3])
        assertEquals(1, document.blankLines)
        assertEquals(64, document.digest.length)
    }

    @Test fun malformedQuotesHeadersAndUtf8FailWithoutReturningPartialRows() {
        failure(CsvProblem.MALFORMED_QUOTES) { read(header + "2026-11-02,1,DEBIT,\"unfinished") }
        failure(CsvProblem.MALFORMED_QUOTES) { read(header + "2026-11-02,1,DEBIT,\"closed\"x,Food,\n") }
        failure(CsvProblem.INVALID_HEADER) { read("date,DATE\n") }
        failure(CsvProblem.INVALID_UTF8) { CsvReader.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream()) }
    }

    @Test fun byteRowColumnAndFieldCapsAreEnforced() {
        val stream = object : InputStream() {
            var remaining = CsvReader.MAX_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 120 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, length)
                bytes.fill(120, offset, offset + count); remaining -= count; return count
            }
        }
        failure(CsvProblem.TOO_LARGE) { CsvReader.read(stream) }
        failure(CsvProblem.FIELD_TOO_LONG) { read(header + "x".repeat(CsvReader.MAX_FIELD + 1)) }
        failure(CsvProblem.TOO_MANY_COLUMNS) { read((0..64).joinToString(",") { "col$it" }) }
        val row = "2026-11-02,0.01,DEBIT,Synthetic,Food,\n"
        assertEquals(10_000, read(header + row.repeat(10_000)).rows.size)
        failure(CsvProblem.TOO_MANY_ROWS) { read(header + row.repeat(10_001)) }
    }

    @Test fun dateFormatsAreExplicitAndNeverGuessed() {
        val document = read(header + "03/04/2026,1.00,DEBIT,Synthetic,Food,\n03/04,1.00,DEBIT,Synthetic,Food,\n")
        val initial = planCsvImport(ledger(), request(document), clock)
        assertEquals(2, initial.invalidCount)
        val base = request(document)
        val dayFirst = planCsvImport(ledger(), base.copy(mapping = base.mapping.copy(dateFormat = CsvDateFormat.DAY_FIRST)), clock)
        val monthFirst = planCsvImport(ledger(), base.copy(mapping = base.mapping.copy(dateFormat = CsvDateFormat.MONTH_FIRST)), clock)
        assertEquals(LocalDate.of(2026, 4, 3), dayFirst.rows.first().parsed.date)
        assertEquals(LocalDate.of(2026, 3, 4), monthFirst.rows.first().parsed.date)
        assertEquals(1, dayFirst.invalidCount)
    }

    @Test fun splitColumnsRequireExactlyOnePositiveUnsignedAmount() {
        val document = read("date,debit,credit,merchant\n2026-11-02,1.25,0,Synthetic\n2026-11-02,,2.00,Synthetic\n2026-11-02,1,2,Synthetic\n2026-11-02,0,0,Synthetic\n2026-11-02,-1,,Synthetic\n")
        val mapping = CsvMapping.forHeaders(document.headers).copy(mode = CsvAmountMode.DEBIT_CREDIT_COLUMNS)
        val plan = planCsvImport(ledger(), request(document, mapping = mapping), clock)
        assertEquals(3, plan.invalidCount)
        assertEquals(125L, plan.debitMinor)
        assertEquals(200L, plan.creditMinor)
    }

    @Test fun genericHundredRowsHaveIndependentExactCountsAndPreserveIdenticalOccurrences() {
        val document = File(System.getProperty("financegenie.fixtures")).parentFile.resolve("csv/acceptance-100.csv").inputStream().use(CsvReader::read)
        val bankLedger = ledger().copy(accounts = ledger().accounts.map { it.copy(value = it.value.copy(openingDate = LocalDate.of(2026, 11, 1))) })
        val initial = planCsvImport(bankLedger, request(document), clock)
        assertEquals(100, initial.rows.size)
        assertEquals(10, initial.invalidCount)
        assertEquals(90, initial.reviewCount)
        assertEquals(0, initial.acceptedCount)
        assertEquals(78_550L, initial.debitMinor)
        assertEquals(20_000L, initial.creditMinor)
        val choices = initial.rows.filter { it.parsed.problem == null && it.parsed.direction == CsvDirection.DEBIT }
            .associate { it.parsed.number to CsvDecision(CsvAction.CREATE, categoryId = it.parsed.categoryId) }
        val accepted = planCsvImport(bankLedger, request(document, choices), clock)
        assertEquals(80, accepted.acceptedCount)
        assertEquals(10, accepted.reviewCount)
        assertEquals(80, accepted.writes.map { it.transaction.id }.distinct().size)
        assertEquals(2, accepted.writes.count { it.details.merchant == "Identical synthetic transit" })
        assertEquals(80, accepted.evidence.map { it.sourceKey }.distinct().size)
        assertTrue(accepted.canCommit)
    }

    @Test fun duplicateSuggestionsRequireReviewAndMatchingDoesNotCreateAnotherExpense() {
        val document = read(header + "2026-11-02,1.25,DEBIT,Synthetic,Food,\n")
        val existing = TransactionRecord(LedgerTransaction(UUID(0, 100), TransactionKind.EXPENSE, 125, bank, LocalDate.of(2026, 11, 2), categoryId = "food"), TransactionDetails("A different manual description"), metadata)
        val initial = planCsvImport(ledger(listOf(existing)), request(document), clock)
        assertEquals(1, initial.duplicateCount)
        assertEquals(0, initial.acceptedCount)
        val unsafe = planCsvImport(ledger(listOf(existing)), request(document, mapOf(2 to CsvDecision(CsvAction.CREATE, categoryId = "food"))), clock)
        assertFalse(unsafe.canCommit)
        val matched = planCsvImport(ledger(listOf(existing)), request(document, mapOf(2 to CsvDecision(CsvAction.ATTACH, existingId = existing.transaction.id))), clock)
        assertEquals(1, matched.acceptedCount)
        assertTrue(matched.writes.isEmpty())
        assertEquals(existing.transaction.id, matched.evidence.single().transactionId)
    }

    @Test fun twoStatementLegsCanMatchOneTransferAcrossAccounts() {
        val transfer = TransactionRecord(LedgerTransaction(UUID(0, 100), TransactionKind.TRANSFER, 125, bank, LocalDate.of(2026, 11, 2), destinationAccountId = cash), TransactionDetails("Synthetic transfer"), metadata)
        val debit = read(header + "2026-11-02,1.25,DEBIT,Different outgoing bank description,,REF-D\n")
        val credit = read(header + "2026-11-02,1.25,CREDIT,Different incoming bank description,,REF-C\n")
        for (input in listOf(request(debit), request(credit).copy(accountId = cash))) {
            val plan = planCsvImport(ledger(listOf(transfer)), input.copy(decisions = mapOf(2 to CsvDecision(CsvAction.ATTACH, existingId = transfer.transaction.id))), clock)
            assertTrue(plan.canCommit)
            assertTrue(plan.writes.isEmpty())
        }
    }

    @Test fun oneExistingMovementCannotAbsorbTwoLegitimateIdenticalRows() {
        val document = read(header + "2026-11-02,1.25,DEBIT,Synthetic,Food,\n".repeat(2))
        val existing = TransactionRecord(LedgerTransaction(UUID(0, 100), TransactionKind.EXPENSE, 125, bank, LocalDate.of(2026, 11, 2), categoryId = "food"), TransactionDetails("Synthetic"), metadata)
        val plan = planCsvImport(ledger(listOf(existing)), request(document, (2..3).associateWith { CsvDecision(CsvAction.ATTACH, existingId = existing.transaction.id) }), clock)
        assertEquals(1, plan.acceptedCount)
        assertEquals(1, plan.reviewCount)
    }

    @Test fun creditsAndUnknownCategoriesNeedExplicitClassification() {
        val document = read(header + "2026-11-02,1.25,CREDIT,Synthetic,Unknown,\n")
        assertEquals(1, planCsvImport(ledger(), request(document), clock).reviewCount)
        val unconfirmed = CsvDecision(CsvAction.CREATE, TransactionKind.REFUND, "food")
        assertFalse(planCsvImport(ledger(), request(document, mapOf(2 to unconfirmed)), clock).canCommit)
        val confirmed = planCsvImport(ledger(), request(document, mapOf(2 to unconfirmed.copy(confirmedUnlinked = true))), clock)
        assertTrue(confirmed.canCommit)
        assertEquals(TransactionKind.REFUND, confirmed.writes.single().transaction.kind)
    }

    @Test fun remappingAndChangingAccountCannotBypassOriginalFileReplayIdentity() {
        val document = read(header + "2026-11-02,1.25,DEBIT,Synthetic,Food,REF\n")
        val first = planCsvImport(ledger(), request(document), clock)
        val batch = ImportBatchRecord(UUID(0, 400), bank, first.fingerprint, 1, true, 0)
        val remapped = request(document).copy(accountId = cash, mapping = request(document).mapping.copy(merchant = -1))
        val plan = planCsvImport(ledger().copy(importBatches = listOf(batch)), remapped, clock)
        assertNotEquals(first.fingerprint, plan.fingerprint)
        assertEquals(batch, plan.replay)
        assertFalse(plan.canCommit)
    }

    @Test fun ambiguousCategoryNamesRequireAnExplicitChoice() {
        val document = read(header + "2026-11-02,1.25,DEBIT,Synthetic,Food,\n")
        val original = ledger()
        val ambiguous = original.copy(categories = original.categories + StoredRecord(LedgerCategory("second-food", "FOOD"), metadata))
        val plan = planCsvImport(ambiguous, request(document), clock)
        assertNull(plan.rows.single().parsed.categoryId)
        assertEquals(1, plan.reviewCount)
        val chosen = planCsvImport(ambiguous, request(document, mapOf(2 to CsvDecision(CsvAction.CREATE, categoryId = "food"))), clock)
        assertTrue(chosen.canCommit)
    }
}
