package com.financegenie.android.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class GoldenFixtureTest {
    private val fixture: JsonObject = File(
        requireNotNull(System.getProperty("financegenie.fixtures")),
        "november-baseline.json",
    ).reader().use { JsonParser.parseReader(it).asJsonObject }

    private val calculator = LedgerCalculator(Clock.fixed(Instant.parse(fixture.text("clockInstant")), ZoneOffset.UTC))
    private val month = YearMonth.parse(fixture.text("reportMonth"))

    @Test
    fun sharedFixtureMatchesEveryIndependentlyRecordedMetric() {
        assertEquals(1, fixture.get("schemaVersion").asInt)
        assertEquals(LEDGER_CURRENCY, fixture.text("currency"))
        assertEquals(LedgerDates.reportingZone.id, fixture.text("reportingZone"))
        val snapshot = snapshot()
        val report = calculator.monthlyReport(snapshot, month)
        val expected = fixture.getAsJsonObject("expected")
        assertEquals(expected.minor("grossSpendingMinor"), report.grossSpendingMinor)
        assertEquals(expected.minor("refundsMinor"), report.refundsMinor)
        assertEquals(expected.minor("netSpendingMinor"), report.netSpendingMinor)
        assertEquals(expected.minor("incomeMinor"), report.incomeMinor)
        assertEquals(expected.minor("recordedSurplusMinor"), report.recordedSurplusMinor)
        assertEquals(expected.minor("adjustmentsMinor"), report.adjustmentsMinor)
        assertEquals(expected.minor("totalTrackedPositionMinor"), report.totalTrackedPositionMinor)
        assertEquals(expected.amountMap("accountPositionsMinor").mapKeys { UUID.fromString(it.key) }, report.accountPositionsMinor)
        assertEquals(expected.amountMap("categoryNetSpendingMinor"), report.categoryNetSpendingMinor)
        assertEquals(expected.amountMap("dailyNetSpendingMinor").mapKeys { LocalDate.parse(it.key) }, report.dailyNetSpendingMinor)
        assertEquals(expected.amountMap("monthlyNetSpendingMinor").mapKeys { YearMonth.parse(it.key) }, report.monthlyNetSpendingMinor)
        snapshot.budgets.forEach { budget ->
            assertEquals(
                expected.getAsJsonObject("budgetRemainingMinor").minor(budget.id.toString()),
                calculator.budgetProgress(budget, report).remainingMinor,
            )
        }
    }

    @Test
    fun fixtureTotalsDoNotDependOnTransactionOrAccountOrdering() {
        val snapshot = snapshot()
        val expected = calculator.monthlyReport(snapshot, month)
        repeat(25) { seed ->
            val shuffled = snapshot.copy(
                accounts = snapshot.accounts.shuffled(Random(seed)),
                transactions = snapshot.transactions.shuffled(Random(seed)),
                categories = snapshot.categories.reversed(),
            )
            assertEquals(expected, calculator.monthlyReport(shuffled, month))
        }
    }

    @Test fun sharedFixtureMatchesAnalyticsAndScopedAccountViews() {
        val snapshot = snapshot()
        val window = AnalyticsWindow.forSelection(snapshot, month, false, LocalDate.of(2026, 12, 31))
        val reports = calculator.reports(snapshot, listOf(window.period, window.daily, window.monthly).map { ReportRequest(it) })
        val analytics = buildAnalytics(snapshot, reports[0], reports[1], reports[2])
        assertEquals(349_900L, analytics.report.netSpendingMinor)
        assertEquals(mapOf("groceries" to 200_000L, "shopping" to 149_900L), analytics.categories.associate { it.category.id to it.netSpendingMinor })
        assertEquals(-25_075L, analytics.days.single { it.date == LocalDate.of(2026, 11, 3) }.netSpendingMinor)
        assertEquals(349_900L, analytics.months.last().netSpendingMinor)
        val bank = calculator.report(snapshot, window.period, TransactionSelection(accountId = UUID(0, 1)))
        val card = calculator.report(snapshot, window.period, TransactionSelection(accountId = UUID(0, 3)))
        assertEquals(200_000L, bank.netSpendingMinor)
        assertEquals(149_900L, card.netSpendingMinor)
        assertEquals(6_000_000L, bank.incomeMinor)
        assertEquals(0L, card.incomeMinor)
        assertEquals(bank.accountPositionsMinor, card.accountPositionsMinor)
    }

    private fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        accounts = fixture.getAsJsonArray("accounts").map { element ->
            val row = element.asJsonObject
            LedgerAccount(
                id = UUID.fromString(row.text("id")),
                name = row.text("name"),
                kind = AccountKind.valueOf(row.text("kind")),
                openingDate = LocalDate.parse(row.text("openingDate")),
                openingBalanceMinor = row.minor("openingBalanceMinor"),
            )
        },
        categories = fixture.getAsJsonArray("categories").map { element ->
            val row = element.asJsonObject
            LedgerCategory(row.text("id"), row.text("name"))
        },
        transactions = fixture.getAsJsonArray("transactions").map { element ->
            val row = element.asJsonObject
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
        budgets = fixture.getAsJsonArray("budgets").map { element ->
            val row = element.asJsonObject
            MonthlyBudget(
                id = UUID.fromString(row.text("id")),
                month = YearMonth.parse(row.text("month")),
                limitMinor = row.minor("limitMinor"),
                categoryId = row.optional("categoryId"),
            )
        },
    )

    private fun JsonObject.text(key: String): String {
        val value = get(key).asJsonPrimitive
        require(value.isString) { "Fixture monetary/text fields must use JSON strings" }
        return value.asString
    }

    private fun JsonObject.optional(key: String): String? = if (has(key)) text(key) else null

    private fun JsonObject.minor(key: String): Long = Money.decodeMinor(text(key), allowZero = true, signed = true)

    private fun JsonObject.amountMap(key: String): Map<String, Long> = getAsJsonObject(key).keySet()
        .associateWith { name -> getAsJsonObject(key).minor(name) }
}
