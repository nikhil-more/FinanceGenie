# Local ledger storage

The storage API is `LedgerRepository`, implemented by `RoomLedgerRepository`.
`FinanceGenieDatabase.open(applicationContext)` opens the private
`financegenie-ledger.db` file. Keep one application-scoped instance per active
profile; the Step 4 application container now shares it with all management/entry
screens. Do not open a new database per screen. Draft revisions remain fixed while
a form is open; stale edits must be reopened rather than silently rebased.

## Records and write rules

- Schema v1 has ten tables: ledger settings, accounts, categories, transactions,
  budgets, balance observations, source evidence, import batches, change groups
  and prior-version change items. Settings seed a local UUID, INR currency,
  Asia/Kolkata reporting zone and
  Uncategorized only. No synthetic financial records are seeded in the app.
- Monetary columns are SQLite INTEGER / Kotlin Long. Calendar dates/months use
  ISO text, event instants preserve exact UTC ISO values including fractional
  seconds, and local creation/update metadata uses UTC epoch milliseconds.
- Domain models stay free of Room annotations. Storage records add revisions,
  timestamps, merchant/notes and normalized provenance. No raw notification/SMS
  body, bank credentials, auth tokens, cloud outbox or AI data is stored here.
- All writes go through the repository. It reads/validates the ledger, writes
  changes/history/evidence and increments the ledger revision inside one Room
  transaction. No destructive migration fallback or SQLite REPLACE is used.
- A new record uses `expectedRevision = null`; an edit requires its current
  revision. Duplicate IDs and stale revisions fail explicitly. Financial amounts
  and links are validated by the existing pure domain contract before commit.
- Transfers are a single row with two account references. Linked refund changes
  can be submitted in one group, with deferred foreign-key checks. Archival keeps
  historical references; there is no public hard-delete ledger operation.
- Budget uniqueness uses `(month, scope_key)`, where scope is `overall` or
  `category:<id>`. It does not depend on SQLite's nullable-column uniqueness.
- Budget edits keep their month/scope fixed. Removing a budget deletes only that
  configuration row after checking its revision. `copyBudgetsToNextMonth` checks
  the preview's ledger revision, inserts new IDs for missing active scopes in one
  transaction, preserves existing target values and skips archived categories.
  A failed copy rolls back all rows/revision; an empty copy changes no revision.
- Undo preserves prior typed rows and validates the whole result. It is allowed
  only while every affected row is still at the group's accepted revision. Undo
  increments revisions; creation undo retains a tombstone, rather than deleting
  history and allowing silent replay. This is local undo, not an immutable bank audit.
- Source keys are unique within source type. Financial writes and evidence commit
  atomically. Undo detaches evidence added to pre-existing records while retaining
  its key; evidence of newly tombstoned records remains available for replay review.
  Import-batch bookkeeping is schema-only until Step 7 implements CSV workflows.

`observe()` watches the ledger revision and reads a complete snapshot in a Room
transaction. It can conflate intermediate revisions; it must never emit a partial
financial group. Do not combine separate account/transaction table flows into an
unvalidated report. Paged/filter queries are for feeds, not complete account balances.

Step 6 adds a single snapshot cache per repository. Each read checks settings and
SQLite change counters inside a Room transaction. A changed ledger revision,
connection-local `total_changes()` or cross-connection `PRAGMA data_version`
reloads every table. The additional counters prevent a snapshot read inside a
rolled-back outer transaction from being reused when a later commit reaches the
same revision. Tests cover direct nesting, dispatcher changes and a second DB
connection. Reusing an unchanged snapshot avoids reading the same 10,000-row
ledger again immediately before a write. Writes still validate complete before/
after snapshots and preserve atomic history/evidence. This trades retaining one
snapshot in memory for less repeated disk work; all mutations must continue to
advance the revision through the repository.
The cache follows SQLite's distinction between
[connection-local changes](https://www.sqlite.org/c3ref/total_changes.html) and
[commits from other connections](https://www.sqlite.org/pragma.html#pragma_data_version).
Discard the repository when its database is closed/replaced; restore must create
a fresh database/repository rather than reusing connection-specific cache state.

For edits, preserve the record's original source/details and supply its revision:

```kotlin
val current = repository.read().transactions.first { it.transaction.id == id }
val groupId = repository.applyTransactions(
    listOf(
        TransactionWrite(
            transaction = current.transaction.copy(isDeleted = true),
            details = current.details,
            expectedRevision = current.metadata.revision,
        ),
    ),
)
repository.undoTransactionChange(groupId)
```

## Schema and recovery policy

Room/KSP and the Room Gradle plugin export the canonical schema to
`apps/android/schemas/com.financegenie.android.data.local.FinanceGenieDatabase/1.json`.
Review and version that file with changes. CI rejects uncommitted generated schema
changes. Room owns its JSON formatting, including the lack of a final newline;
repository hygiene still parses the JSON and checks for recognizable credentials.

Schema v1 creation/reopen and fresh-process persistence are tested on the user's
phone. No upgrade exists yet, so no artificial version 2 or migration test is
invented. The first real schema change must bump the version, provide a preserving
migration and test a populated v1 fixture before shipping. Do not rewrite a shipped
version's schema or fall back to destructive recreation.

The database relies on the app sandbox/platform storage protections. It is not
claimed to be application-encrypted, and a sync/OS backup is not a portable recovery
feature. Versioned export/restore and local deletion are Step 8 requirements before
real personal use. The [tracker](../progress_tracker.md) records remaining gates.
