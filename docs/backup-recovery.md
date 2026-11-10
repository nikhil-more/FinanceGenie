# Personal backup, export and deletion

Open **Settings → Backup, export and delete**. This is one person's local ledger;
there is no sign-in, shared workspace or cloud recovery service.

## Save and recover

**Save full JSON backup** first explains that the file is plaintext and includes
private notes and deleted records. Confirm, then use Android's system save picker
to choose its location. Export writes a coherent committed snapshot; subsequent
edits need a new backup. No permanent URI grant or private copy of the export is
kept. A write/close failure is reported and the app attempts to remove only that
new, incomplete provider document. If removal fails, remove it at its save location.

**Choose JSON backup to restore** reads and validates the file without changing
the current ledger. Review the counts, including deleted transactions. Restore
explicitly replaces the local ledger, so save its current backup first if needed.
Cancel discards the in-memory preview; rotation retains it, process death does not.

Restore creates an isolated empty database with a fresh local identity, preserves
financial IDs/fields/notes/revisions, and remaps CSV profile/file identities from
stored component hashes. Reference hashes are account-scoped and financial account
IDs remain unchanged. The old profile remains active during parsing/staging. The
complete staged database and minimal import receipts are re-read and compared
before an atomic active-file pointer is published. A changed current ledger
revision rejects an old confirmation. Retired repositories reject new writes;
the activity drops old ViewModels/drafts/navigation and reloads the selected ledger.

After selection, old database handles close and only app-owned obsolete profile
databases/WAL/SHM/journal files are removed. Startup resumes cleanup of abandoned
stages or obsolete files. A cleanup failure is displayed and can be retried; it
is not reported as complete local deletion. Privacy settings remain on the device;
an enabled app lock requires authentication again after the switch.

## Backup version 1

The fixed-depth UTF-8 JSON envelope contains `format: "FinanceGenieBackup"`, numeric
`schemaVersion: 1`, integer-string `createdAtMillis`, mandatory per-table `counts`,
`ledger`, and a lowercase hexadecimal `sha256`. Money, revisions, counts and epoch
milliseconds are canonical base-10 integer strings; booleans/nulls retain JSON
types. Monetary data never passes through floating point. Strict token types,
unique/known fields, valid UTF-8/Unicode, references, IDs, financial constraints,
metadata and checked totals are validated before any staging writes.

| Table | Maximum records |
|---|---:|
| Accounts | 1,000 |
| Categories | 10,000 |
| Transactions, including tombstones | 100,000 |
| Budgets | 100,000 |
| Balance observations | 100,000 |
| Source evidence | 200,000 |
| Import batches | 10,000 |
| Minimal import receipts | 200,000 |

The complete input/output limit is **50 MiB**. Individual model limits still apply:
account/category names 80 characters, merchant 200, notes 2,000, adjustment reason
500 and source key 512. JSON strings have an additional 2,000-character ceiling;
flat records have at most 32 members and names at most 64 characters. Dates/months
use supported years 0001–9999. Unknown future schema, missing/duplicate fields,
unsafe numeric types, malformed/truncated text, oversized input or invalid
references fail without changing the selected ledger. Limits are upper bounds,
not a claim that every maximum-size combination fits within 50 MiB.

The SHA-256 detects accidental changes, **not authenticity or encryption**. It
covers compact canonical serialization of `createdAtMillis` plus `ledger` using
the version-1 writer's fixed field order and ordered entity arrays. Counts are
independently compared with parsed table sizes. The checked-in codec defines
serialization; this is not a claim of RFC 8785 JSON canonicalization.

Included: settings/currency/reporting zone, active/archived accounts/categories,
transactions/tombstones with all details/timestamps/links, budgets, observations,
normalized evidence, batch replay/undone status and minimal import ownership/
revision guards. These guards preserve safe CSV batch undo/restore after recovery.
Excluded: old manual undo snapshots and prior financial payloads, security
preferences/credentials, device permissions, unfinished previews/candidates,
owner bindings, sessions, outbox work and original statements. There is no cloud
upload or old session to resume. Room schema remains v1.

## CSV report

**Save transaction CSV report** discloses plaintext/private notes and spreadsheet
safety transformations. It exports active transactions in date/ID order, with
positive exact INR amounts and one row per transfer, plus type/accounts/category/
merchant/notes/source/refund and adjustment information. All cells are quoted;
quotes are doubled and record endings are CRLF. Text beginning with `=`, `+`, `-`,
`@`, including after leading whitespace, or starting with tab/CR/LF is prefixed
with an apostrophe. This transformation is explicit; JSON preserves original text.
CSV omits configuration, tombstones and replay metadata and cannot recover the
full ledger. It is separate from the generic bank-statement CSV import format.

## Delete locally

**Delete all local financial data** requires confirmation against the current
ledger revision. It activates a fresh empty ledger, clears old ledger databases/
sidecars and transient ledger UI state, and retains only the empty ledger's
Uncategorized/default configuration. No background queue exists in milestone A.
Exports at user-selected locations remain, and app-lock/screen-protection settings
are retained deliberately. The app does not promise physical secure erasure from
flash storage or remote removal of previously exported copies.

## Reproduce recovery checks

```bash
./gradlew :apps:android:testDebugUnitTest :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest :apps:android:lintDebug :apps:android:assembleRelease
./gradlew -PfinancegenieTestBuildType=recoveryCheck :apps:android:assembleRecoveryCheck :apps:android:assembleRecoveryCheckAndroidTest
python3 scripts/verify_recovery_on_device.py
```

The driver uses test-named profiles/preferences for storage/UI checks and only
its unique synthetic Downloads exports. It inspects their exact counts/formula
escaping and removes those files afterward. Keep the phone unlocked for pickers.
`--storage-only`, `--ui-only` and `--drill-only` isolate the three groups.

The genuine fresh-install drill uses **FinanceGenie Recovery Check**,
`com.financegenie.android.recoverycheck`, with its own sandbox. It exports 1,000
synthetic records, uninstalls only that synthetic package, reinstalls, proves its
ledger is empty, supplies the exact same backup, restores, then verifies it in a
new process. It also exercises replay-safe CSV undo/restore and durable deletion.
The user's FinanceGenie Dev ledger is never uninstalled, cleared or used as a
fixture. The check package is disposable and is removed after retaining evidence.

Three coordinated tests force-stop only the check app at actual storage boundaries:
an insert inside the pending staged transaction, after pointer publication before
old-file cleanup, and deletion after empty-profile publication before cleanup.
Fresh readers verify the old complete ledger, the new complete ledger, or the
new empty ledger respectively, then cleanup. Test wrappers are instrumentation
code only. This checks process interruption, not destructive power-loss testing.

The 1,000-row device fixture includes a linked refund, cash/card transfers, private
notes, one tombstone, budgets, a balance observation and a CSV batch. Independent
expectations: spending **₹3,258.49**, income **₹60,000**, Bank **₹51,741.51**,
Cash **₹5,000**, Card **₹0**. Counts/IDs/every financial field and import guards
must match after restoring to a fresh local identity. Actual outcomes belong in
the verification log and progress tracker.

References: [Android system document access](https://developer.android.com/training/data-storage/shared/documents-files),
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile),
[pinned Gson streaming reader](https://github.com/google/gson/blob/gson-parent-2.11.0/gson/src/main/java/com/google/gson/stream/JsonReader.java).
