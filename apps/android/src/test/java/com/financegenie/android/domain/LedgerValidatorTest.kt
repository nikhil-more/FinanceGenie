package com.financegenie.android.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerValidatorTest {
    private val validator = LedgerValidator(testClock)

    @Test
    fun duplicateIdsAndDuplicateBudgetScopesAreRejected() {
        val entry = transaction()
        assertThrows(IllegalArgumentException::class.java) { validator.validate(ledger(entry, entry)) }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger().copy(accounts = listOf(accounts()[0], accounts()[0])))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger().copy(categories = listOf(LedgerCategory.uncategorized(), LedgerCategory.uncategorized())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger().copy(budgets = listOf(foodBudget(), foodBudget().copy(id = UUID(0, 201)))))
        }
    }

    @Test
    fun unknownReferencesAndMissingReservedCategoryAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validator.validate(ledger(transaction(account = UUID(0, 999)))) }
        assertThrows(IllegalArgumentException::class.java) { validator.validate(ledger(transaction(category = "missing"))) }
        assertThrows(IllegalArgumentException::class.java) { validator.validate(ledger().copy(categories = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger().copy(budgets = listOf(foodBudget().copy(categoryId = "missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger(transaction(kind = TransactionKind.TRANSFER, destination = UUID(0, 999))))
        }
    }

    @Test
    fun futureTransactionsAndEntriesBeforeEitherOpeningAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger(transaction(date = LocalDate.of(2027, 1, 1))))
        }
        val lateBank = accounts().map { if (it.id == bankId) it.copy(openingDate = LocalDate.of(2026, 11, 3)) else it }
        assertThrows(IllegalArgumentException::class.java) { validator.validate(ledger(transaction()).copy(accounts = lateBank)) }
        val lateCash = accounts().map { if (it.id == cashId) it.copy(openingDate = LocalDate.of(2026, 11, 3)) else it }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger(transaction(kind = TransactionKind.TRANSFER, destination = cashId)).copy(accounts = lateCash))
        }
    }

    @Test
    fun eventInstantAndEffectiveDateMustAgreeInIndia() {
        val timed = transaction(date = LocalDate.of(2026, 10, 31)).copy(
            occurredAt = Instant.parse("2026-10-31T18:30:00Z"),
            originalZoneId = "UTC",
        )
        assertThrows(IllegalArgumentException::class.java) { validator.validate(ledger(timed)) }
        assertThrows(IllegalArgumentException::class.java) { transaction().copy(occurredAt = Instant.EPOCH) }
    }

    @Test
    fun transferRequiresExactlyTwoDistinctAccountsAndPositiveAmount() {
        assertThrows(IllegalArgumentException::class.java) { transaction(kind = TransactionKind.TRANSFER) }
        assertThrows(IllegalArgumentException::class.java) { transaction(kind = TransactionKind.TRANSFER, destination = bankId) }
        assertThrows(IllegalArgumentException::class.java) { transaction(destination = cashId) }
        assertThrows(IllegalArgumentException::class.java) { transaction(amount = 0) }
        assertThrows(IllegalArgumentException::class.java) { transaction(amount = -1) }
        assertThrows(IllegalArgumentException::class.java) { transaction(amount = Money.INPUT_MAX_MINOR + 1) }
    }

    @Test
    fun partialRefundsCanUseAnotherAccountButCannotExceedThePurchase() {
        val expense = transaction(amount = 10_000)
        val first = transaction(101, TransactionKind.REFUND, 6_000, account = cardId, originalExpense = expense.id)
        val second = transaction(102, TransactionKind.REFUND, 4_000, account = cashId, originalExpense = expense.id)
        validator.validate(ledger(expense, first, second))
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger(expense, first, second.copy(amountMinor = 4_001)))
        }
    }

    @Test
    fun refundRequiresAnExpenseLinkOrAnExplicitUnlinkedConfirmation() {
        assertThrows(IllegalArgumentException::class.java) { transaction(kind = TransactionKind.REFUND) }
        assertThrows(IllegalArgumentException::class.java) { transaction(kind = TransactionKind.REFUND, confirmed = true, category = null) }
        validator.validate(ledger(transaction(kind = TransactionKind.REFUND, confirmed = true)))
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger(transaction(kind = TransactionKind.REFUND, originalExpense = UUID(0, 999))))
        }
        val income = transaction(kind = TransactionKind.INCOME)
        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(ledger(income, transaction(101, TransactionKind.REFUND, originalExpense = income.id)))
        }
    }

    @Test
    fun reducingOrDeletingPurchaseRequiresAtomicRefundResolution() {
        val expense = transaction(amount = 10_000)
        val refund = transaction(101, TransactionKind.REFUND, 5_000, originalExpense = expense.id)
        val before = ledger(expense, refund)
        assertThrows(IllegalArgumentException::class.java) {
            validator.validateChange(before, ledger(expense.copy(amountMinor = 4_999), refund))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validateChange(before, ledger(expense.copy(isDeleted = true), refund))
        }
        validator.validateChange(before, ledger(expense.copy(isDeleted = true), refund.copy(isDeleted = true)))
        validator.validateChange(before, ledger(expense, refund.copy(isDeleted = true)))
    }

    @Test
    fun archivedReferencesRemainValidButCannotBeNewlySelected() {
        val original = ledger(transaction())
        val archived = original.copy(
            accounts = original.accounts.map { if (it.id == bankId) it.copy(isArchived = true) else it },
            categories = original.categories.map { if (it.id == "food") it.copy(isArchived = true) else it },
        )
        validator.validateChange(original, archived)
        validator.validateChange(archived, archived.copy(transactions = listOf(transaction(amount = 30_000))))
        assertThrows(IllegalArgumentException::class.java) {
            validator.validateChange(archived, archived.copy(transactions = archived.transactions + transaction(101)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validateChange(archived, archived.copy(budgets = listOf(foodBudget())))
        }
    }

    @Test
    fun changingToAnArchivedDestinationOrCategoryIsRejected() {
        val snapshot = ledger(transaction(category = null)).let { original ->
            original.copy(
                accounts = original.accounts.map { if (it.id == cashId) it.copy(isArchived = true) else it },
                categories = original.categories.map { if (it.id == "food") it.copy(isArchived = true) else it },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validateChange(snapshot, snapshot.copy(transactions = listOf(transaction())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.validateChange(snapshot, snapshot.copy(transactions = listOf(transaction(kind = TransactionKind.TRANSFER, destination = cashId))))
        }
    }

    @Test
    fun currencyAndAdjustmentShapeCannotBeMisrepresented() {
        assertThrows(IllegalArgumentException::class.java) { accounts()[0].copy(currency = "USD") }
        assertThrows(IllegalArgumentException::class.java) { transaction(kind = TransactionKind.ADJUSTMENT) }
        assertThrows(IllegalArgumentException::class.java) {
            transaction(kind = TransactionKind.ADJUSTMENT, direction = AdjustmentDirection.INCREASE, reason = " ")
        }
        assertThrows(IllegalArgumentException::class.java) { transaction(direction = AdjustmentDirection.DECREASE) }
        assertThrows(IllegalArgumentException::class.java) { LedgerCategory(UNCATEGORIZED_ID, "Uncategorized", isArchived = true) }
    }
}
