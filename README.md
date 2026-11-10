# FinanceGenie

An offline-first personal-finance Android app built with Kotlin, Jetpack Compose
and Material 3. Accounts, categories and manual expense/income/transfer/refund/
adjustment entry use the tested local ledger, with search, edit, delete and undo.
Implementation follows [plan.md](plan.md). Current status, test dependencies and
step-by-step tradeoffs are recorded in [progress_tracker.md](progress_tracker.md).
The Overview shows scoped totals, signed category/daily/monthly charts with exact
text alternatives and recent matching activity. Its filters are shared with
Transactions. The Budgets tab supports independent
overall/category limits, month planning, explicit next-month copying and in-app
warnings, always against the full calendar month.
Settings includes CSV import with column/date mapping, an explicit review preview,
duplicate suggestions and replay protection, plus batch history with safe undo/
restore. See [the CSV guide](docs/csv-import.md) for the supported personal-statement
format and synthetic examples.
Settings also provides full JSON backup/restore, a spreadsheet-safe transaction
CSV report and confirmed local deletion. Backups preserve notes, tombstones and
CSV replay guards; restore verifies a new database before switching to it. See
[personal recovery](docs/backup-recovery.md) for the plaintext-file disclosure,
limits and recovery drill.

## Open and build

Open this repository root in Android Studio. Use JDK 17 and install Android SDK
Platform 34 and Build Tools 34.0.0 through SDK Manager. Set `ANDROID_HOME` to the
SDK directory, or create an untracked `local.properties` file containing:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

The checked-in Gradle wrapper supplies Gradle; a separate Gradle installation is
not required. First-time dependency downloads need network access. Once all
dependencies are cached, add `--offline` to Gradle commands.

```bash
./gradlew :apps:android:assembleDebug
```

The development APK is written to
`apps/android/build/outputs/apk/debug/android-debug.apk`. It installs as
`com.financegenie.android.debug` with the name **FinanceGenie Dev**, keeping its
data separate from a future release build. Minimum Android version is 8.0 (API 26).
Select the Android app module (`:apps:android`) in Android Studio and run it on a device or AVD.

## Checks

```bash
python3 scripts/check_repository.py
./gradlew :apps:android:assembleDebug :apps:android:testDebugUnitTest :apps:android:lintDebug :apps:android:assembleDebugAndroidTest :apps:android:assembleRelease
```

The Python guard requires Python 3.11+ and checks whitespace, JSON/XML/TOML syntax,
wrapper integrity and a limited set of recognizable credential patterns. It is
not a complete Kotlin formatter or comprehensive secret scanner. Android lint
provides the initial Android static analysis. JVM tests cover exact money,
ledger validation, account/report/budget calculations and the shared synthetic
fixture under `fixtures/ledger/`. These run without an Android runtime.
CSV parser, mapping and classification tests use synthetic `fixtures/csv/` files.

Navigation, entry, privacy and activity-recreation tests are in `src/androidTest/`. With a running
Android emulator or connected test device, run:

```bash
./gradlew :apps:android:connectedDebugAndroidTest
./gradlew :apps:android:installDebug
adb shell am start -n com.financegenie.android.debug/com.financegenie.android.MainActivity
```

For the storage suite plus the two-stage process-restart check, with the phone
connected and both APKs built, run:

```bash
python3 scripts/verify_storage_on_device.py
python3 scripts/verify_storage_on_device.py --ui
```

The script installs with `-r`, stops only FinanceGenie Dev between the restart
probe's stages, and verifies that the reader runs in a different process. Tests
clean up only their own in-memory/test-named databases and preference file. They
never clear the application's ledger or the phone's data. Device serials are
selected privately and redacted from output; `--serial` is available for multiple phones.
Use `--ui-only` to rerun UI checks and their restart probe independently.
Use `--budget-ui-only` for budget UI checks plus the expense/budget restart probe.
Use `--analytics-ui-only` for the golden/filter/edit and empty/single/1,000-row
analytics checks. For the isolated 10,000-record physical-phone performance setup,
see [the benchmark guide](docs/performance.md).

For the CSV UI/system-picker and interrupted-commit recovery checks, run:

```bash
python3 scripts/verify_csv_on_device.py
```

This driver creates/removes only its unique synthetic Downloads file and test
database. It verifies a real interrupted SQLite transaction in a fresh process,
then durable undo/replay protection and explicit restore. See [CSV reproduction
and the 10,000-row baseline](docs/csv-import.md#verification-and-reproduction).

For backup/restore/export/deletion checks and a genuine fresh-install drill:

```bash
./gradlew -PfinancegenieTestBuildType=recoveryCheck :apps:android:assembleRecoveryCheck :apps:android:assembleRecoveryCheckAndroidTest
python3 scripts/verify_recovery_on_device.py
```

The separate **FinanceGenie Recovery Check** package contains synthetic test data
only. The driver can uninstall/reinstall that package to verify recovery without
clearing FinanceGenie Dev. Keep the phone unlocked for its real save/open picker
checks. Storage/UI/drill subsets and interruption boundaries are documented in
[the recovery guide](docs/backup-recovery.md#reproduce-recovery-checks).

Manually check dark mode, rotation, back navigation, 200% font size and TalkBack.
The user supplies a connected physical phone for device tests; the current
reference phone is an A063 running Android 15 / API 35. Record test outcomes and
tradeoffs in `progress_tracker.md`; never commit a device serial or personal data.
CI builds the app and test APK and runs lint/repository checks; connected UI tests
and device launch remain a separate required verification gate. A release APK from
this setup is unsigned; production signing and distribution are not configured.

## Pinned foundation

| Component | Version |
|---|---|
| JDK | 17 |
| Gradle | 8.9, with distribution checksum |
| Android Gradle Plugin | 8.6.0 |
| Kotlin / Compose compiler plugin | 2.0.20 |
| Compose BOM | 2024.09.00 |
| Room / schema plugin | 2.6.1 |
| KSP | 2.0.20-1.0.25 |
| Coroutines | 1.8.1 |
| Compile / target / minimum SDK | 34 / 34 / 26 |

These versions match the installed SDK and cached dependencies available for this
private foundation. They are not presented as the latest releases. Upgrade the
toolchain and target SDK together before public distribution; see
[the decision record](docs/decisions.md).

## Data and project boundaries

The app starts with an empty ledger and Uncategorized, with no seeded financial
records, network integration or sensitive permission request. Cloud sync,
notification access and AI are not configured.
It is for individual use, with one person's own accounts and private ledger.
Team spaces, organizational roles and business administration are outside scope.
OS cloud/device-transfer backup exclusions are explicit; actual device behavior
must still be verified. The app has no application-level database encryption.
User-selected JSON/CSV exports are plaintext, with an explicit disclosure before
the save picker. Optional app lock uses the Android device credential; screen
protection is enabled by default. See [the entry/privacy guide](docs/manual-entry.md)
for behavior and manual phone checks. Never commit real financial examples, local configuration,
signing keys or service credentials. Use synthetic data for later tests.

- [Product requirements](docs/product-requirements.md)
- [Progress and step tradeoffs](progress_tracker.md)
- [Decisions](docs/decisions.md)
- [Threat model](docs/security-threat-model.md)
- [Verification log](docs/verification-log.md)
- [Local data model and write boundaries](docs/local-data.md)
- [Calendar budgets and copy behavior](docs/budgets.md)
- [Offline Overview, charts and filter scope](docs/analytics.md)
- [CSV mapping, review, replay and batch recovery](docs/csv-import.md)
- [Personal JSON recovery, CSV report and local deletion](docs/backup-recovery.md)
- [Performance methodology and reproduction](docs/performance.md)
- [Acceptance checklist](docs/e2e-acceptance.md)

Licensed under [Apache License 2.0](LICENSE).
