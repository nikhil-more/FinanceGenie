package com.financegenie.android.recovery

import com.financegenie.android.data.StoredLedger
import java.io.OutputStream
import java.math.BigDecimal

object CsvReport {
    /** Quoting alone does not neutralize spreadsheet formulas. Preserve originals in JSON. */
    fun cell(value: String): String {
        val unsafe = value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')
        return "\"" + (if (unsafe) "'" else "") + value.replace("\"", "\"\"") + "\""
    }
    fun write(ledger: StoredLedger, output: OutputStream) {
        val accounts = ledger.accounts.associate { it.value.id to it.value.name }
        val categories = ledger.categories.associate { it.value.id to it.value.name }
        val writer = output.bufferedWriter(Charsets.UTF_8)
        fun row(values: List<String>) { writer.write(values.joinToString(",", transform = ::cell)); writer.write("\r\n") }
        row(listOf("id", "date", "type", "amount_INR", "account", "destination_account", "category", "merchant", "notes", "source", "original_expense_id", "adjustment_direction", "adjustment_reason"))
        ledger.transactions.filterNot { it.transaction.isDeleted }.sortedWith(compareBy({ it.transaction.effectiveDate }, { it.transaction.id })).forEach { (v, d) ->
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            row(listOf(v.id.toString(), v.effectiveDate.toString(), v.kind.name, BigDecimal.valueOf(v.amountMinor, 2).toPlainString(),
                accounts.getValue(v.accountId), v.destinationAccountId?.let(accounts::getValue).orEmpty(), v.categoryId?.let(categories::getValue).orEmpty(),
                d.merchant, d.notes, d.source.name, v.originalExpenseId?.toString().orEmpty(), v.adjustmentDirection?.name.orEmpty(), v.adjustmentReason.orEmpty()))
        }
        writer.flush()
    }
}
