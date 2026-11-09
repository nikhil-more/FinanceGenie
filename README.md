# FinanceGenie

An offline-first personal-finance Android app built with Kotlin, Jetpack Compose
and Material 3. Accounts, categories and manual expense/income/transfer/refund/
adjustment entry use the tested local ledger, with search, edit, delete and undo.
Implementation follows [plan.md](plan.md). Current status, test dependencies and
step-by-step tradeoffs are recorded in [progress_tracker.md](progress_tracker.md).
The Overview shows basic calculated totals. Calendar-budget management and charts
remain Steps 5–6; the Budgets tab currently explains its empty state.

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
OS cloud/device-transfer backup exclusions are explicit; actual device behavior
must still be verified. The app has no application-level database encryption or
portable backup yet. Optional app lock uses the Android device credential; screen
protection is enabled by default. See [the entry/privacy guide](docs/manual-entry.md)
for behavior and manual phone checks. Never commit real financial examples, local configuration,
signing keys or service credentials. Use synthetic data for later tests.

- [Product requirements](docs/product-requirements.md)
- [Progress and step tradeoffs](progress_tracker.md)
- [Decisions](docs/decisions.md)
- [Threat model](docs/security-threat-model.md)
- [Verification log](docs/verification-log.md)
- [Local data model and write boundaries](docs/local-data.md)
- [Acceptance checklist](docs/e2e-acceptance.md)

Licensed under [Apache License 2.0](LICENSE).
