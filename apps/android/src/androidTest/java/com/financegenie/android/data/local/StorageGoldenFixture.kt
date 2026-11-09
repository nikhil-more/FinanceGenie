package com.financegenie.android.data.local

import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.domain.AccountKind
import com.financegenie.android.domain.LedgerAccount
import com.financegenie.android.domain.LedgerCategory
import com.financegenie.android.domain.LedgerSnapshot
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.Money
import com.financegenie.android.domain.MonthlyBudget
import com.financegenie.android.domain.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.json.JSONObject

internal data class StorageGoldenFixture(val snapshot: LedgerSnapshot, val expected: JSONObject, val month: YearMonth) {
    companion object {
        fun load(): StorageGoldenFixture {
            val json = InstrumentationRegistry.getInstrumentation().context.assets.open("november-baseline.json")
                .bufferedReader().use { JSONObject(it.readText()) }
            require(Instant.parse(json.text("clockInstant")) == storageClock.instant())
            val snapshot = LedgerSnapshot(
                accounts = json.rows("accounts").map { row ->
                    LedgerAccount(
                        UUID.fromString(row.text("id")), row.text("name"), AccountKind.valueOf(row.text("kind")),
                        LocalDate.parse(row.text("openingDate")), row.minor("openingBalanceMinor"),
                    )
                },
                categories = json.rows("categories").map { row -> LedgerCategory(row.text("id"), row.text("name")) },
                transactions = json.rows("transactions").map { row ->
                    LedgerTransaction(
                        id = UUID.fromString(row.text("id")),
                        kind = TransactionKind.valueOf(row.text("kind")),
                        amountMinor = row.minor("amountMinor"),
                        accountId = UUID.fromString(row.text("accountId")),
                        effectiveDate = LocalDate.parse(row.text("effectiveDate")),
                        destinationAccountId = row.optional("destinationAccountId")?.let(UUID::fromString),
                        categoryId = row.optional("categoryId"),
                        originalExpenseId = row.optional("originalExpenseId")?.let(UUID::fromString),
                    )
                },
                budgets = json.rows("budgets").map { row ->
                    MonthlyBudget(UUID.fromString(row.text("id")), YearMonth.parse(row.text("month")), row.minor("limitMinor"), row.optional("categoryId"))
                },
            )
            return StorageGoldenFixture(snapshot, json.getJSONObject("expected"), YearMonth.parse(json.text("reportMonth")))
        }
    }
}

internal fun JSONObject.minor(key: String): Long = Money.decodeMinor(text(key), allowZero = true, signed = true)

private fun JSONObject.text(key: String): String {
    val value = get(key)
    require(value is String) { "Fixture money and text fields must be strings" }
    return value
}

private fun JSONObject.optional(key: String): String? = if (has(key)) text(key) else null

private fun JSONObject.rows(key: String): List<JSONObject> = getJSONArray(key).let { array ->
    (0 until array.length()).map { array.getJSONObject(it) }
}
