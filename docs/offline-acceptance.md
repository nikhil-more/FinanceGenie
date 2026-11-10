# Offline acceptance for individual use

Step 9 verifies the existing local app before optional notification, sync or web
work. Current gate status and unresolved checks live in
[the progress tracker](../progress_tracker.md). Test outcomes belong in
[the verification log](verification-log.md); an unrun human check is not a pass.

## Isolated release-like candidate

`acceptance` inherits the release configuration, uses the production Application,
profile store, Room repository and real clock, and disables debugging. It is
signed with the local debug key solely for installation on a test device. Its
package is `com.financegenie.android.acceptance`, labelled **FinanceGenie
Acceptance**. The normal **FinanceGenie Dev** ledger and privacy preferences are
preserved. Production signing and distribution remain unconfigured.

```bash
./gradlew -PfinancegenieTestBuildType=acceptance :apps:android:assembleAcceptance :apps:android:assembleAcceptanceAndroidTest
./gradlew -PfinancegenieTestBuildType=backupControl :apps:android:assembleBackupControl :apps:android:assembleBackupControlAndroidTest
python3 scripts/verify_acceptance_on_device.py --output /tmp/financegenie-offline-acceptance
```

The driver uninstalls/reinstalls only its disposable acceptance package, so do not
store personal records in it. Keep the phone unlocked and leave it untouched
while automated UI checks run. `--smoke-only` and `--backup-only` can reproduce the
independent checks; the summary explicitly records which checks ran. The driver
rejects failed, skipped and missing mandatory tests.

The smoke test starts empty, creates bank/cash/card accounts and Food, enters the
six golden events through the forms, and independently expects:

| Result | Exact value |
|---|---:|
| Recorded salary | ₹60,000.00 |
| Net spending | ₹3,499.00 |
| Bank | ₹55,501.00 |
| Cash | ₹1,000.00 |
| Card | ₹0.00 |
| Total tracked position | ₹56,501.00 |
| Remaining against ₹5,000 overall budget | ₹1,501.00 |

It edits a purchase, checks recalculation, deletes the purchase with its linked
refund and undoes both. A process-local time-zone change leaves reporting in
Asia/Kolkata without changing the phone's settings. A malformed backup preserves
the ledger; confirmed local deletion empties it, and restore activates a new
complete profile with equal records. A separate process verifies the restored
six-event ledger and exact totals. Existing CSV/picker/interrupted-write and
fresh-install recovery drivers remain required alongside this smoke test.

## Actual framework backup filtering

The backup check seeds six invented canaries across credential/device-protected
files, preferences and SQLite. It invokes Android's default `BackupAgent`
extraction into a bounded pipe using the candidate's packaged backup rules. On
API 31+, cloud and device-transfer destinations are exercised separately; older
Android uses its legacy full-backup rules. The candidate must produce zero bytes.

A separate `backupControl` package intentionally permits backup. It must extract
all six canaries, proving that an empty candidate output was not caused by a
broken reader. The driver removes the control in `finally`. Neither normal app
uses this permissive overlay. Hidden framework construction is allowed only in
this instrumentation process with `am instrument --no-hidden-api-checks` on API
28+; production has no such reflection or bypass.

The driver's before/after check requires the phone's backup-enabled setting to
stay unchanged. It never selects a transport, enables backup or changes the
phone's security settings. This proves actual framework extraction/filtering;
it does not claim a physical OEM migration or cloud restore. Full JSON backup
and fresh-install recovery provide the independent app recovery path.

## Android version coverage and human review

CI builds the APKs once and runs the local storage/UI, CSV, recovery and acceptance
drivers on API 26 and API 34 emulators. The physical reference phone is API 35.
Emulator checks establish behavior, not physical-phone performance or human
accessibility. SDK setup requests `platform-tools` explicitly because the
[action's v3 default](https://github.com/android-actions/setup-android/blob/v3/action.yml)
includes the removed legacy `tools` package.

Before closing Gate A, use TalkBack in the synthetic candidate to review Overview
chart text, a transaction form, budget limits/remaining/status, CSV mapping and
preview, and the backup/deletion disclosures. Cancel destructive actions during
this review. Confirm real device-credential lock/cancel/return behavior and secure
screenshots/recents. Keep credentials on the phone. Automated 200% font/dark-mode
checks complement this human walkthrough.

The measured budget Save P95 of 314.52 ms is an explicitly accepted individual-use
tradeoff; its original 300 ms target remains recorded as missed. Other current
performance results and measurement limitations are in
[the performance guide](performance.md).
