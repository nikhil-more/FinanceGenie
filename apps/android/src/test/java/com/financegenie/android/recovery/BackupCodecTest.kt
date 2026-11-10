package com.financegenie.android.recovery

import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

internal val recoveryClock: Clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC)
internal fun recoveryFixture(count: Int = 1_000): PortableBackup {
    val bank = UUID(0, 1); val cash = UUID(0, 2); val card = UUID(0, 3)
    val meta = RecordMetadata(1, 0, 0)
    val date = LocalDate.of(2026, 11, 2)
    val expense = UUID(0, 102)
    val events = listOf(
        LedgerTransaction(UUID(0, 101), TransactionKind.INCOME, 6_000_000, bank, date),
        LedgerTransaction(expense, TransactionKind.EXPENSE, 275_025, bank, date, categoryId = "food"),
        LedgerTransaction(UUID(0, 103), TransactionKind.REFUND, 25_075, bank, date, categoryId = "food", originalExpenseId = expense),
        LedgerTransaction(UUID(0, 104), TransactionKind.TRANSFER, 500_000, bank, date, destinationAccountId = cash),
        LedgerTransaction(UUID(0, 105), TransactionKind.EXPENSE, 74_900, card, date, categoryId = "food"),
        LedgerTransaction(UUID(0, 106), TransactionKind.TRANSFER, 74_900, bank, date, destinationAccountId = card),
        LedgerTransaction(UUID(0, 107), TransactionKind.ADJUSTMENT, 1, bank, date, adjustmentDirection = AdjustmentDirection.INCREASE, adjustmentReason = "Synthetic correction"),
        LedgerTransaction(UUID(0, 108), TransactionKind.EXPENSE, 1_250, bank, date, categoryId = "food", isDeleted = true),
        LedgerTransaction(UUID(0, 109), TransactionKind.REFUND, 50, bank, date, categoryId = "food", originalExpenseId = expense, isDeleted = true),
    ) + (0 until (count - 9)).map { LedgerTransaction(UUID(1, it.toLong()), TransactionKind.EXPENSE, 1, bank, date, categoryId = "food") }
    val ledger = StoredLedger(LedgerSettings(UUID(7, 7), "INR", "Asia/Kolkata", 9),
        listOf(LedgerAccount(bank, "Synthetic Bank", AccountKind.BANK, date.minusDays(1), 10_000),
            LedgerAccount(cash, "Synthetic Cash", AccountKind.CASH, date.minusDays(1), -300),
            LedgerAccount(card, "Synthetic Card", AccountKind.CREDIT_CARD, date.minusDays(1), -500)).map { StoredRecord(it, meta) },
        listOf(LedgerCategory.uncategorized(), LedgerCategory("food", "Food"), LedgerCategory("archived", "Archived", true)).map { StoredRecord(it, meta) },
        events.map { TransactionRecord(it, TransactionDetails("Synthetic, \"café\" 🪔\nmerchant", "=SUM(A1)\tprivate synthetic note\nnext line"), meta) },
        listOf(StoredRecord(MonthlyBudget(UUID(3, 1), YearMonth.of(2026, 11), 500_000), meta), StoredRecord(MonthlyBudget(UUID(3, 2), YearMonth.of(2026, 11), 150_000, "food"), meta)),
        listOf(StoredRecord(BalanceObservation(UUID(4, 1), bank, date, 10_000), meta)), emptyList())
    return PortableBackup(ledger, emptyList(), 1_794_000_000_000)
}

class BackupCodecTest {
    private fun bytes(backup: PortableBackup = recoveryFixture()): ByteArray = ByteArrayOutputStream().also { BackupCodec.write(backup, it, recoveryClock) }.toByteArray()
    private fun reject(block: () -> Unit) { try { block(); fail("Expected invalid backup rejection") } catch (_: Exception) {} }

    @Test fun thousandRecordRoundTripPreservesEveryFieldAndExactIndependentTotals() {
        val original = recoveryFixture()
        val json = bytes(original)
        val restored = BackupCodec.read(json.inputStream(), recoveryClock)
        assertEquals(original.ordered(), restored.ordered())
        assertEquals(1_000, restored.ledger.transactions.size)
        assertEquals(2, restored.ledger.transactions.count { it.transaction.isDeleted })
        val report = LedgerCalculator(recoveryClock).monthlyReport(restored.ledger.snapshot, YearMonth.of(2026, 11))
        assertEquals(325_841L, report.netSpendingMinor)
        assertEquals(6_000_000L, report.incomeMinor)
        assertEquals(-500L, report.accountPositionsMinor[UUID(0, 3)])
        assertTrue(json.toString(Charsets.UTF_8).contains("\"amountMinor\":\"275025\""))
        assertFalse(json.toString(Charsets.UTF_8).contains("password"))
    }

    @Test fun rejectsUnsupportedSchemaTruncationTrailingDataDuplicateAndUnknownFields() {
        val json = bytes().toString(Charsets.UTF_8)
        listOf(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), json.dropLast(5), json + "{}",
            json.replaceFirst("\"format\":", "\"extra\":{},\"format\":"),
            json.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            json.replaceFirst("\"amountMinor\":\"", "\"amountMinor\":\"0"),
            json.replaceFirst("\"amountMinor\":\"275025\"", "\"amountMinor\":275025")).forEach {
            reject { BackupCodec.read(it.byteInputStream(), recoveryClock) }
        }
    }

    @Test fun corruptionAndWrongCountsAreDetected() {
        val root = JsonParser.parseString(bytes().toString(Charsets.UTF_8)).asJsonObject
        root.getAsJsonObject("counts").addProperty("transactions", "999")
        reject { BackupCodec.read(root.toString().byteInputStream(), recoveryClock) }
        val json = bytes().toString(Charsets.UTF_8).replace("Synthetic Bank", "Modified Bank")
        reject { BackupCodec.read(json.byteInputStream(), recoveryClock) }
    }

    @Test fun malformedUtf8DeepValuesAndLongFieldsAreRejected() {
        reject { BackupCodec.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream(), recoveryClock) }
        val json = bytes().toString(Charsets.UTF_8)
        reject { BackupCodec.read(json.replace("\"INR\"", "[".repeat(2000) + "0" + "]".repeat(2000)).byteInputStream(), recoveryClock) }
        reject { BackupCodec.read(json.replace("Synthetic Bank", "x".repeat(2001)).byteInputStream(), recoveryClock) }
        reject { BackupCodec.read(json.replace("Synthetic Bank", "\\uD800").byteInputStream(), recoveryClock) }
    }

    @Test fun oversizeWhitespaceIsRejectedWithoutUnboundedRead() {
        val input = object : InputStream() {
            var remaining = BackupCodec.MAX_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 32 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len.toLong(), remaining).toInt(); b.fill(32, off, off+n); remaining -= n; return n
            }
        }
        reject { BackupCodec.read(input, recoveryClock) }
    }

    @Test fun invalidReferencesDuplicateIdsAndEntityCapsFailBeforeExport() {
        val fixture = recoveryFixture()
        val broken = fixture.ledger.transactions.first().let { it.copy(transaction = it.transaction.copy(accountId = UUID(99, 99))) }
        reject { bytes(fixture.copy(ledger = fixture.ledger.copy(transactions = listOf(broken)))) }
        reject { bytes(fixture.copy(ledger = fixture.ledger.copy(accounts = fixture.ledger.accounts + fixture.ledger.accounts.first()))) }
        reject { bytes(fixture.copy(ledger = fixture.ledger.copy(transactions = List(100_001) { fixture.ledger.transactions.first() }))) }
    }

    @Test fun csvQuotesNewlinesAndNeutralizesFormulaPrefixesWithoutChangingBackupText() {
        listOf("=1+1", "+cmd", "-1+2", "@SUM(A1)", "  =1", "\ttext", "\rtext", "\ntext").forEach {
            assertTrue(CsvReport.cell(it).startsWith("\"'"))
        }
        assertEquals("\"hello, \"\"world\"\"\nnext\"", CsvReport.cell("hello, \"world\"\nnext"))
        val output = ByteArrayOutputStream(); CsvReport.write(recoveryFixture().ledger, output)
        val text = output.toString("UTF-8")
        assertTrue(text.contains("\"'=SUM(A1)"))
        assertFalse(text.contains(UUID(0, 108).toString())) // Deleted events are not report rows.
        assertTrue(bytes().toString(Charsets.UTF_8).contains("=SUM(A1)"))
    }
}
