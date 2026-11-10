package com.financegenie.android.recovery

import com.financegenie.android.csv.csvFileIdentity
import com.financegenie.android.csv.digestParts
import com.financegenie.android.data.*
import com.financegenie.android.domain.*
import java.time.Clock
import java.util.UUID

/** Only ownership/revision guards, never the disposable prior financial payload. */
data class ImportReceipt(val batchId: UUID, val transactionId: UUID, val afterRevision: Long, val created: Boolean)
data class PortableBackup(val ledger: StoredLedger, val receipts: List<ImportReceipt>, val createdAtMillis: Long) {
    fun ordered(): PortableBackup = copy(ledger = ledger.copy(accounts = ledger.accounts.sortedBy { it.value.id },
        categories = ledger.categories.sortedBy { it.value.id }, transactions = ledger.transactions.sortedBy { it.transaction.id },
        budgets = ledger.budgets.sortedBy { it.value.id }, observations = ledger.observations.sortedBy { it.value.id },
        evidence = ledger.evidence.sortedBy { it.input.id }, importBatches = ledger.importBatches.sortedBy { it.id }),
        receipts = receipts.sortedWith(compareBy({ it.batchId }, { it.transactionId })))
    fun counts(): Map<String, Int> = linkedMapOf("accounts" to ledger.accounts.size,
        "categories" to ledger.categories.size, "transactions" to ledger.transactions.size,
        "budgets" to ledger.budgets.size, "observations" to ledger.observations.size,
        "evidence" to ledger.evidence.size, "importBatches" to ledger.importBatches.size, "importReceipts" to receipts.size)

    fun validate(clock: Clock) {
        require(createdAtMillis >= 0)
        counts().forEach { (name, count) -> require(count <= BackupCodec.limits.getValue(name)) }
        LedgerValidator(clock).validate(ledger.snapshot)
        val accounts = ledger.accounts.map { it.value.id }.toSet()
        val events = ledger.transactions.associateBy { it.transaction.id }
        val batches = ledger.importBatches.associateBy { it.id }
        require(batches.size == ledger.importBatches.size)
        require(ledger.importBatches.map { it.fileIdentity }.distinct().size == batches.size)
        require(ledger.evidence.map { it.input.id }.distinct().size == ledger.evidence.size)
        require(ledger.evidence.map { it.input.source to it.input.sourceKey }.distinct().size == ledger.evidence.size)
        require(receipts.map { it.batchId to it.transactionId }.distinct().size == receipts.size)
        val byBatch = ledger.evidence.filter { it.input.importBatchId != null }.groupBy { it.input.importBatchId!! }
        val guards = receipts.groupBy { it.batchId }
        require(byBatch.values.map { it.first().changeGroupId }.distinct().size == byBatch.size)
        ledger.importBatches.forEach { batch ->
            require(batch.accountId in accounts && batch.createdAtMillis >= 0)
            val pieces = batch.fingerprint.split(':')
            require(pieces[3] == csvFileIdentity(ledger.settings.localId, pieces[1]))
            require(pieces[4] == digestParts(pieces[3], batch.accountId.toString(), pieces[2]))
            val evidence = byBatch[batch.id].orEmpty()
            require(evidence.size == batch.acceptedCount && evidence.map { it.changeGroupId }.distinct().size == 1)
            require(evidence.map { it.input.transactionId }.toSet() == guards[batch.id].orEmpty().map { it.transactionId }.toSet())
            evidence.forEach { item ->
                require(item.input.source == TransactionSource.CSV && item.isActive != batch.isUndone)
                val key = item.input.sourceKey.split(':')
                require(key.size == 4 && key[0] == "fgcsv1" && key[1] == batch.fileIdentity)
                require(key[2].toInt() in 2..10_001 && (key[3] == "none" || Regex("[a-f0-9]{64}").matches(key[3])))
            }
        }
        ledger.evidence.forEach {
            require(it.input.transactionId in events && it.recordedAtMillis >= 0)
            it.input.importBatchId?.let { id -> require(id in batches) }
            require(it.input.importBatchId != null || !it.input.sourceKey.startsWith("fgcsv1:"))
        }
        receipts.forEach {
            require(it.batchId in batches && it.transactionId in events && it.afterRevision in 1..events.getValue(it.transactionId).metadata.revision)
            require(!it.created || events.getValue(it.transactionId).details.source == TransactionSource.CSV)
        }
        require(receipts.filter { it.created }.map { it.transactionId }.distinct().size == receipts.count { it.created })
        ledger.transactions.forEach { record ->
            record.transaction.originalExpenseId?.let { require(events[it]?.transaction?.kind == TransactionKind.EXPENSE) }
            require(record.transaction.effectiveDate.year in 1..9999)
        }
        ledger.accounts.forEach { require(it.value.openingDate.year in 1..9999) }
        ledger.budgets.forEach { require(it.value.month.year in 1..9999) }
        ledger.observations.forEach { require(it.value.asOfDate.year in 1..9999) }
        val metadata = ledger.accounts.map { it.metadata } + ledger.categories.map { it.metadata } +
            ledger.transactions.map { it.metadata } + ledger.budgets.map { it.metadata } + ledger.observations.map { it.metadata }
        require(metadata.all { it.createdAtMillis >= 0 && it.updatedAtMillis >= it.createdAtMillis })
        // Exercise checked arithmetic before creating a database, including signed positions.
        LedgerCalculator(clock).monthlyReport(ledger.snapshot, java.time.YearMonth.from(LedgerDates.today(clock)))
    }

    fun forNewProfile(id: UUID): PortableBackup {
        require(id != ledger.settings.localId)
        val batches = ledger.importBatches.map { batch ->
            val p = batch.fingerprint.split(':')
            val identity = csvFileIdentity(id, p[1])
            batch.copy(fingerprint = "csv1:${p[1]}:${p[2]}:$identity:${digestParts(identity, batch.accountId.toString(), p[2])}")
        }
        val identities = batches.associate { it.id to it.fileIdentity }
        return copy(ledger = ledger.copy(settings = ledger.settings.copy(localId = id, revision = 1),
            importBatches = batches, evidence = ledger.evidence.map { item ->
                val batch = item.input.importBatchId
                if (batch == null) item else {
                    val parts = item.input.sourceKey.split(':')
                    item.copy(input = item.input.copy(sourceKey = "fgcsv1:${identities.getValue(batch)}:${parts[2]}:${parts[3]}"))
                }
            }))
    }
}
