package com.financegenie.android.recovery

import com.financegenie.android.csv.*
import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal val recoveryBank = UUID(0, 1)
internal val recoveryCash = UUID(0, 2)
internal val recoveryCard = UUID(0, 3)
internal const val recoveryCsv = "date,amount,direction,merchant,category,reference\n2026-11-03,0.07,DEBIT,Synthetic imported,Food,SYN-RECOVERY-ONLY\n"
internal suspend fun seedRecovery(repository: LedgerRepository) {
    val date = LocalDate.of(2026, 11, 2)
    listOf(LedgerAccount(recoveryBank, "Synthetic Bank", AccountKind.BANK, date.minusDays(1)),
        LedgerAccount(recoveryCash, "Synthetic Cash", AccountKind.CASH, date.minusDays(1)),
        LedgerAccount(recoveryCard, "Synthetic Card", AccountKind.CREDIT_CARD, date.minusDays(1))).forEach { repository.saveAccount(it) }
    repository.saveCategory(LedgerCategory("food", "Food"))
    repository.saveCategory(LedgerCategory("old", "Archived synthetic", true))
    val original = UUID(0, 102)
    val events = listOf(
        LedgerTransaction(UUID(0, 101), TransactionKind.INCOME, 6_000_000, recoveryBank, date),
        LedgerTransaction(original, TransactionKind.EXPENSE, 275_025, recoveryBank, date, categoryId = "food"),
        LedgerTransaction(UUID(0, 103), TransactionKind.REFUND, 25_075, recoveryBank, date, categoryId = "food", originalExpenseId = original),
        LedgerTransaction(UUID(0, 104), TransactionKind.TRANSFER, 500_000, recoveryBank, date, destinationAccountId = recoveryCash),
        LedgerTransaction(UUID(0, 105), TransactionKind.EXPENSE, 74_900, recoveryCard, date, categoryId = "food"),
        LedgerTransaction(UUID(0, 106), TransactionKind.TRANSFER, 74_900, recoveryBank, date, destinationAccountId = recoveryCard),
    ) + (0 until 993).map { LedgerTransaction(UUID(1, it.toLong()), TransactionKind.EXPENSE, 1, recoveryBank, date, categoryId = "food") }
    repository.applyTransactions(events.map { TransactionWrite(it, TransactionDetails("Synthetic café, \"shop\"\nnext line", "=SUM(A1)\nSynthetic private note")) })
    val deleted = repository.read().transactions.single { it.transaction.id == UUID(1, 0) }
    repository.applyTransactions(listOf(TransactionWrite(deleted.transaction.copy(isDeleted = true), deleted.details, deleted.metadata.revision)))
    val document = CsvReader.read(recoveryCsv.byteInputStream())
    repository.commitCsv(CsvImportRequest(document, CsvMapping.forHeaders(document.headers), recoveryBank,
        mapOf(2 to CsvDecision(CsvAction.CREATE, categoryId = "food")), repository.read().settings.revision))
    repository.saveBudget(MonthlyBudget(UUID(3, 1), YearMonth.of(2026, 11), 500_000))
    repository.saveBudget(MonthlyBudget(UUID(3, 2), YearMonth.of(2026, 11), 150_000, "food"))
    repository.saveObservation(BalanceObservation(UUID(4, 1), recoveryBank, date.plusDays(1), 5_174_151))
}
