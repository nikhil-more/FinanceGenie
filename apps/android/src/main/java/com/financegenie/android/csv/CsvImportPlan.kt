package com.financegenie.android.csv

import com.financegenie.android.data.ImportBatchRecord
import com.financegenie.android.data.SourceEvidenceInput
import com.financegenie.android.data.StoredLedger
import com.financegenie.android.data.TransactionDetails
import com.financegenie.android.data.TransactionRecord
import com.financegenie.android.data.TransactionSource
import com.financegenie.android.data.TransactionWrite
import com.financegenie.android.domain.AdjustmentDirection
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.LedgerTransaction
import com.financegenie.android.domain.LedgerValidator
import com.financegenie.android.domain.Money
import com.financegenie.android.domain.TransactionKind
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

enum class CsvAction { REVIEW, EXCLUDE, CREATE, ATTACH }
data class CsvDecision(val action: CsvAction = CsvAction.REVIEW, val kind: TransactionKind = TransactionKind.EXPENSE,
    val categoryId: String? = null, val otherAccountId: UUID? = null, val originalExpenseId: UUID? = null,
    val confirmedUnlinked: Boolean = false, val existingId: UUID? = null, val duplicateOverride: Boolean = false)
data class CsvImportRequest(val document: CsvDocument, val mapping: CsvMapping, val accountId: UUID,
    val decisions: Map<Int, CsvDecision>, val expectedLedgerRevision: Long)
data class CsvReviewedRow(val parsed: CsvParsedRow, val matches: List<TransactionRecord>, val decision: CsvDecision, val accepted: Boolean)
data class CsvImportPlan(val rows: List<CsvReviewedRow>, val writes: List<TransactionWrite>, val evidence: List<SourceEvidenceInput>,
    val fingerprint: String, val replay: ImportBatchRecord?, val blockingError: Boolean,
    val debitMinor: Long, val creditMinor: Long, val acceptedDebitMinor: Long, val acceptedCreditMinor: Long) {
    val acceptedCount get() = rows.count { it.accepted }
    val invalidCount get() = rows.count { it.parsed.problem != null }
    val excludedCount get() = rows.count { it.parsed.problem == null && it.decision.action == CsvAction.EXCLUDE }
    val reviewCount get() = rows.size - acceptedCount - invalidCount - excludedCount
    val duplicateCount get() = rows.count { it.matches.isNotEmpty() }
    val canCommit get() = acceptedCount > 0 && !blockingError && replay == null
}

private data class FlowKey(val date: LocalDate, val amount: Long, val direction: CsvDirection)
private fun LedgerTransaction.flow(account: UUID): CsvDirection? = when {
    kind == TransactionKind.TRANSFER && destinationAccountId == account -> CsvDirection.CREDIT
    accountId != account -> null
    kind == TransactionKind.EXPENSE || kind == TransactionKind.TRANSFER -> CsvDirection.DEBIT
    kind == TransactionKind.INCOME || kind == TransactionKind.REFUND -> CsvDirection.CREDIT
    adjustmentDirection == AdjustmentDirection.INCREASE -> CsvDirection.CREDIT
    adjustmentDirection == AdjustmentDirection.DECREASE -> CsvDirection.DEBIT
    else -> null
}
private fun referenceIn(key: String): String? = key.split(':').takeIf { it.size == 4 && it[0] == "fgcsv1" }?.get(3)?.takeUnless { it == "none" }

/** All matching is a suggestion. Only explicit decisions create events or attach evidence. */
fun planCsvImport(ledger: StoredLedger, request: CsvImportRequest, clock: Clock): CsvImportPlan {
    val account = requireNotNull(ledger.accounts.find { it.value.id == request.accountId }?.value)
    require(!account.isArchived) { "Cannot select an archived account" }
    val parsed = parseCsvRows(request.document, request.mapping, account, ledger.categories.map { it.value }, LedgerDates.today(clock))
    val identity = csvFileIdentity(ledger.settings.localId, request.document.digest)
    val fingerprint = csvFingerprint(ledger.settings.localId, account.id, request.document, request.mapping)
    val replay = ledger.importBatches.firstOrNull { it.fileIdentity == identity }
    val byId = ledger.transactions.associateBy { it.transaction.id }
    val byFlow = ledger.transactions.mapNotNull { record -> record.transaction.flow(account.id)?.let {
        FlowKey(record.transaction.effectiveDate, record.transaction.amountMinor, it) to record
    } }.groupBy({ it.first }, { it.second })
    val references = ledger.evidence.mapNotNull { item -> referenceIn(item.input.sourceKey)?.let { it to item.input.transactionId } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct().mapNotNull { byId[it] } }
    val writes = mutableListOf<TransactionWrite>()
    val evidence = mutableListOf<SourceEvidenceInput>()
    val attached = mutableSetOf<UUID>()
    val rows = parsed.map { row ->
        val decision = request.decisions[row.number] ?: CsvDecision()
        if (row.problem != null) return@map CsvReviewedRow(row, emptyList(), decision, false)
        val key = FlowKey(row.date!!, row.amountMinor!!, row.direction!!)
        val referenceMatches = row.referenceHash?.let { references[it] }.orEmpty()
        // Bank descriptions can differ from manual entries and the other transfer
        // leg. Treat matching financial movement as a review signal regardless
        // of merchant wording; shared index lists keep identical-row cases bounded.
        val matched = if (referenceMatches.isNotEmpty()) referenceMatches else byFlow[key].orEmpty()
        val accepted = try {
            when (decision.action) {
                CsvAction.REVIEW, CsvAction.EXCLUDE -> false
                CsvAction.ATTACH -> {
                    val existing = requireNotNull(matched.find { it.transaction.id == decision.existingId })
                    val event = existing.transaction
                    require(!event.isDeleted && event.effectiveDate == row.date && event.amountMinor == row.amountMinor && event.flow(account.id) == row.direction)
                    require(attached.add(event.id)) { "One movement can be matched only once per file" }
                    evidence += SourceEvidenceInput(UUID.randomUUID(), event.id, TransactionSource.CSV,
                        "fgcsv1:$identity:${row.number}:${row.referenceHash ?: "none"}")
                    true
                }
                CsvAction.CREATE -> {
                    require(matched.isEmpty() || decision.duplicateOverride) { "A possible duplicate needs explicit review" }
                    require(if (row.direction == CsvDirection.DEBIT) decision.kind in setOf(TransactionKind.EXPENSE, TransactionKind.TRANSFER)
                        else decision.kind in setOf(TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.TRANSFER))
                    val original = decision.originalExpenseId?.let { requireNotNull(byId[it]).transaction.also { original ->
                        require(!original.isDeleted && original.kind == TransactionKind.EXPENSE)
                    } }
                    val category = if (original != null) original.spendingCategoryId else decision.categoryId ?: row.categoryId
                    if (decision.kind in setOf(TransactionKind.EXPENSE, TransactionKind.REFUND)) {
                        require(ledger.categories.any { it.value.id == category && !it.value.isArchived })
                    }
                    val transfer = decision.kind == TransactionKind.TRANSFER
                    if (transfer) require(ledger.accounts.any { it.value.id == decision.otherAccountId && !it.value.isArchived && it.value.openingDate <= row.date })
                    val event = LedgerTransaction(row.eventId, decision.kind, row.amountMinor,
                        if (transfer && row.direction == CsvDirection.CREDIT) requireNotNull(decision.otherAccountId) else account.id,
                        row.date, destinationAccountId = if (!transfer) null else if (row.direction == CsvDirection.CREDIT) account.id else decision.otherAccountId,
                        categoryId = category.takeIf { decision.kind in setOf(TransactionKind.EXPENSE, TransactionKind.REFUND) },
                        originalExpenseId = decision.originalExpenseId.takeIf { decision.kind == TransactionKind.REFUND },
                        unlinkedRefundConfirmed = decision.kind == TransactionKind.REFUND && original == null && decision.confirmedUnlinked)
                    writes += TransactionWrite(event, TransactionDetails(row.merchant, source = TransactionSource.CSV))
                    evidence += SourceEvidenceInput(UUID.randomUUID(), event.id, TransactionSource.CSV,
                        "fgcsv1:$identity:${row.number}:${row.referenceHash ?: "none"}")
                    true
                }
            }
        } catch (_: IllegalArgumentException) { false }
        CsvReviewedRow(row, matched, decision, accepted)
    }
    val blocking = try {
        LedgerValidator(clock).validateChange(ledger.snapshot, ledger.snapshot.copy(transactions = ledger.snapshot.transactions + writes.map { it.transaction }))
        false
    } catch (_: IllegalArgumentException) { true }
    fun total(direction: CsvDirection, acceptedOnly: Boolean) = Money.sum(rows.filter {
        it.parsed.problem == null && it.parsed.direction == direction && (!acceptedOnly || it.accepted)
    }.map { it.parsed.amountMinor!! })
    return CsvImportPlan(rows, writes, evidence, fingerprint, replay, blocking,
        total(CsvDirection.DEBIT, false), total(CsvDirection.CREDIT, false), total(CsvDirection.DEBIT, true), total(CsvDirection.CREDIT, true))
}
