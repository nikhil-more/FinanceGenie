# CSV import, review and recovery

Open **Settings → CSV import and history** and choose a document with Android's
system picker. Select an active account, confirm the columns/date format, then
build a preview. No transaction is written by choosing, mapping, previewing or
cancelling. The app requests no broad storage permission or permanent URI grant.
The selected bytes and URI are not retained on disk. Bounded cells and review
choices remain in the ledger ViewModel's memory through rotation; cancel, success
or process death discards them.

## Supported format

The [generic synthetic example](../fixtures/csv/generic-example.csv) uses:

```csv
date,amount,direction,merchant,category,reference
2026-11-02,249.50,DEBIT,Synthetic lunch,Food,SYN-LUNCH-1
```

- UTF-8, optionally with an initial BOM; comma-separated fields, quoted commas/
  newlines, doubled quotes, LF/CRLF/CR record endings. Quotes must enclose a whole
  field. Headers are unique, nonempty and case-insensitively distinct.
- At most 10 MiB, 10,000 data records, 64 columns and 4,000 characters per field.
  Blank records count toward the cap but are ignored with a visible count.
  Numbered preview rows are logical nonblank CSV records, including header row 1;
  a quoted newline does not create another record. Comma-only empty data rows are
  invalid records, not silently ignored blank lines.
- ISO `YYYY-MM-DD` is the default. `DD/MM/YYYY` and `MM/DD/YYYY` are explicit
  alternatives; partial/ambiguous dates are never guessed. Supported years are
  0001–9999. Dates before account opening or after today are excluded as invalid.
  Date-only rows remain date-only financial events.
- Positive plain INR decimals with at most two fractional digits. Signed amounts,
  currency symbols and thousands separators require an adapter and are not guessed.
  Separate debit/credit columns are supported: exactly one must be positive; the
  other may be blank or zero. Two positive values, negatives or two zeros are invalid.
- Merchant/category/reference columns are optional. Limits are 200/80/256 characters
  respectively. Empty categories use Uncategorized; known active category names or
  IDs are resolved with exact ID precedence, then an unambiguous case-insensitive
  name/ID match. Unknown or ambiguous categories require explicit selection.
  No categories are created automatically.

Malformed quoting, invalid UTF-8, invalid headers and structural caps reject the
file without a partial preview. Invalid field/date/amount rows stay visible with
sanitized reasons. Text is displayed as text, never executed or interpreted as markup.

## Classification and matching

Debit/credit describes account balance movement. It does not establish an economic
type. Valid unmatched debits with a known category can be explicitly bulk-confirmed
as expenses. Credits always need review as income, refund or transfer. Transfers
require another active account; refunds require a valid purchase link or explicit
unlinked confirmation. Linked refunds use the original category and the existing
full-ledger refund cap validation.

The preview shows invalid, accepted, excluded, review-required and possible-match
counts, with valid-row and accepted debit/credit totals. Possible matches overlap
the other status counts. Accepted + invalid + excluded + needs-review equals the
CSV record count. Only the accepted subset is committed after a count/account/
totals confirmation. Unresolved or invalid rows are not implicitly accepted.

Reference matching uses trimmed, case-sensitive, account-scoped hashes. Otherwise,
same-account leg/date/amount suggests existing events even when descriptions differ.
This intentionally requires extra review for equal-value movements; wording cannot
silently turn a manual entry or the opposite transfer leg into a second expense.
Original accepted merchant text is preserved. Both legs of an existing transfer can receive evidence
from their respective account statements without creating another financial event.
Matches always require a decision: attach evidence, exclude, or explicitly create
a separate event despite the warning. One existing movement can be matched at most
once per file/account. Identical legitimate rows within a new file retain separate
IDs and row occurrences. Deleted/undone history remains a review signal and is
never silently resurrected by matching.

Refreshing the preview or changing mapping/account clears prior review choices.
An intervening ledger revision requires a refresh/reopened confirmation; selections
are not silently rebased against changed financial data.

## Replay and durable batch operations

The reserved v1 batch/evidence/change-receipt tables are sufficient; this step
does not change the Room schema. `fingerprint` has the canonical versioned form
`csv1:<file SHA-256>:<mapping SHA-256>:<profile/file identity>:<full fingerprint>`.
The final hash binds profile, account, file identity and mapping version. Keeping
the component hashes allows a later restore to re-scope keys to a fresh profile
without retaining file bytes or raw bank references.

The original profile/file identity is checked across accounts and mappings inside
the same Room transaction as commit. Renaming a file or remapping its columns/
account cannot import the same bytes again. Row evidence is
`fgcsv1:<profile/file identity>:<logical row>:<account/reference hash or none>`.
Only hashes and row occurrences are persisted as replay/reference metadata.

Committing a subset reserves that entire file identity. The confirmation explains
that skipped rows need a corrected or reduced file if they are imported later;
overlap matching then requires review. The app does not silently append unreviewed
rows when the original file is selected again. This conservative policy also
blocks byte-identical files assigned to another account in the same profile.

Financial rows, batch status, source evidence, receipts and ledger revision commit
together. Attaching evidence to an existing event preserves all its economic
fields, source, notes and record revision. No original file/path or durable provider
access is kept. History exposes linked transactions for inspection/editing.

Undo soft-deletes only new events created by that batch and deactivates its own
evidence. Existing matched events are never rewritten. Every receipt's revision,
deleted status and new links are checked; any conflict blocks the whole batch.
There is no force or partial batch undo. After corrections, use individual
transaction management rather than rewinding unrelated edits. Generic transaction
undo cannot bypass these import checks.

An undone file remains replay-protected. **Restore batch** explicitly reactivates
its unchanged tombstones/evidence after the same consistency checks. Receipts are
advanced so repeated undo/restore cycles remain revision-checked. Edited rows,
new linked refunds or evidence from later batches require review. Changing values
back manually does not reset their revisions.

## Verification and reproduction

The [100-row synthetic corpus](../fixtures/csv/acceptance-100.csv) contains quoted
commas/newlines/quotes, 80 valid debits (₹785.50), ten valid credits (₹200), ten
invalid rows and two separate identical legitimate debits. With two matching
manual expenses seeded, bulk confirmation accepts 78 rows and leaves twelve for
review. Attaching those two matches and classifying income/transfer/refund accepts
83 rows: 81 new events and two evidence attachments; seven remain in review and
ten invalid. Exact resulting spending is ₹765.50 and recorded income ₹20.

```bash
./gradlew :apps:android:testDebugUnitTest :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest :apps:android:lintDebug :apps:android:assembleRelease
python3 scripts/verify_storage_on_device.py --ui
python3 scripts/verify_csv_on_device.py
```

The CSV driver prepares one uniquely named synthetic Downloads file, selects it
through the real picker and verifies its exact hash, then removes only that file.
The coordinated crash probe uses a test-only SQLite wrapper to stop after an actual
insert inside a pending transaction. The host force-stops only FinanceGenie Dev;
fresh readers verify rollback, preservation of the prior committed batch, durable
undo/replay state and explicit restore. Only its test-named database/PID file is
removed. `--ui-only` and `--crash-only` run these checks independently. These special
tests require their driver arguments; a skipped case is not a device pass.

For the separate non-debuggable performance app:

```bash
./gradlew -PfinancegenieTestBuildType=benchmark :apps:android:assembleBenchmark :apps:android:assembleBenchmarkAndroidTest :benchmarks:assembleBenchmark
python3 scripts/benchmark_on_device.py --csv-only --output /tmp/financegenie-csv-performance
```

It imports 10,000 rows into 10,000 existing synthetic events and verifies all
20,000 rows, provenance and exact totals. Human review time is excluded; parse,
preview/classification, atomic commit and verification are timed separately.
One observation is not a P95 claim. On 10 November 2026 this pipeline completed in
**5,253.70ms**, within the 15-second target. The [reviewed baseline](csv-performance-baseline.json)
contains artifact hashes and limits. **79 JVM, 35 storage and 31 UI tests** plus
four fresh-reader verifications pass; see [the verification log](verification-log.md#step-7--previewed-reversible-csv-imports--2026-11-10)
and [progress tracker](../progress_tracker.md).

References: [Android document access](https://developer.android.com/training/data-storage/shared/documents-files)
and [OpenDocument contract](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument).
