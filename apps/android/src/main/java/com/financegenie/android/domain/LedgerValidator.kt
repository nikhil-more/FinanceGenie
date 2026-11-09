package com.financegenie.android.domain

import java.math.BigInteger
import java.time.Clock

class LedgerValidator(private val clock: Clock = Clock.systemUTC()) {
    fun validate(snapshot: LedgerSnapshot) {
        val today = LedgerDates.today(clock)
        val accounts = snapshot.accounts.associateBy { it.id }
        val categories = snapshot.categories.associateBy { it.id }
        val transactions = snapshot.transactions.associateBy { it.id }
        require(accounts.size == snapshot.accounts.size) { "Account IDs must be unique" }
        require(categories.size == snapshot.categories.size) { "Category IDs must be unique" }
        require(transactions.size == snapshot.transactions.size) { "Transaction IDs must be unique" }
        require(categories.containsKey(UNCATEGORIZED_ID)) { "Uncategorized must exist" }
        require(snapshot.budgets.map { it.id }.distinct().size == snapshot.budgets.size) {
            "Budget IDs must be unique"
        }
        require(snapshot.budgets.map { it.month to it.categoryId }.distinct().size == snapshot.budgets.size) {
            "A month/category scope can have only one budget"
        }
        require(snapshot.observations.map { it.id }.distinct().size == snapshot.observations.size) {
            "Observation IDs must be unique"
        }
        snapshot.accounts.forEach { account ->
            require(account.openingDate <= today) { "Account opening date cannot be in the future" }
        }
        val linkedRefunds = mutableMapOf<java.util.UUID, BigInteger>()
        snapshot.transactions.forEach { transaction ->
            val account = requireNotNull(accounts[transaction.accountId]) { "Account does not exist" }
            require(transaction.effectiveDate >= account.openingDate) { "Transaction predates account opening" }
            require(transaction.effectiveDate <= today) { "Actual transactions cannot be future-dated" }
            if (transaction.occurredAt != null) {
                require(transaction.effectiveDate == LedgerDates.effectiveDate(transaction.occurredAt)) {
                    "Effective date must match the event instant in the ledger reporting zone"
                }
            }
            transaction.destinationAccountId?.let { destinationId ->
                val destination = requireNotNull(accounts[destinationId]) { "Destination account does not exist" }
                require(transaction.effectiveDate >= destination.openingDate) {
                    "Transfer predates destination account opening"
                }
            }
            transaction.categoryId?.let { require(categories.containsKey(it)) { "Category does not exist" } }
            if (!transaction.isDeleted && transaction.originalExpenseId != null) {
                val original = requireNotNull(transactions[transaction.originalExpenseId]) {
                    "Original expense does not exist"
                }
                require(!original.isDeleted && original.kind == TransactionKind.EXPENSE) {
                    "A linked refund requires a non-deleted expense"
                }
                val total = linkedRefunds.getOrDefault(original.id, BigInteger.ZERO)
                    .add(BigInteger.valueOf(transaction.amountMinor))
                require(total <= BigInteger.valueOf(original.amountMinor)) { "Linked refunds exceed the expense" }
                linkedRefunds[original.id] = total
            }
        }
        snapshot.budgets.forEach { budget ->
            budget.categoryId?.let { require(categories.containsKey(it)) { "Budget category does not exist" } }
        }
        snapshot.observations.forEach { observation ->
            val account = requireNotNull(accounts[observation.accountId]) { "Observed account does not exist" }
            require(observation.asOfDate >= account.openingDate && observation.asOfDate <= today) {
                "Observation must be within the recorded account lifetime"
            }
        }
    }

    /** Validate an atomic edit, allowing historical references to archived records. */
    fun validateChange(before: LedgerSnapshot, after: LedgerSnapshot) {
        validate(before)
        validate(after)
        val accounts = after.accounts.associateBy { it.id }
        val categories = after.categories.associateBy { it.id }
        val oldTransactions = before.transactions.associateBy { it.id }
        after.transactions.filterNot { it.isDeleted }.forEach { transaction ->
            val old = oldTransactions[transaction.id]
            if (old == null || old.accountId != transaction.accountId) {
                require(!accounts.getValue(transaction.accountId).isArchived) { "Cannot select an archived account" }
            }
            transaction.destinationAccountId?.let { destination ->
                if (old == null || old.destinationAccountId != destination) {
                    require(!accounts.getValue(destination).isArchived) { "Cannot select an archived destination" }
                }
            }
            val category = transaction.categoryId
            if (category != null && (old == null || old.categoryId != category)) {
                require(!categories.getValue(category).isArchived) { "Cannot select an archived category" }
            }
        }
        val oldBudgets = before.budgets.associateBy { it.id }
        after.budgets.forEach { budget ->
            val category = budget.categoryId
            if (category != null && oldBudgets[budget.id]?.categoryId != category) {
                require(!categories.getValue(category).isArchived) { "Cannot select an archived budget category" }
            }
        }
    }
}
