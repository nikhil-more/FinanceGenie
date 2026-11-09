# FinanceGenie

An offline-first personal-finance Android app built with Kotlin, Jetpack Compose
and Material 3. The current foundation contains navigation for Overview,
Transactions, Budgets and Settings, with light/dark themes and honest empty states.
Transaction storage and finance features are the next stages in [plan.md](plan.md).

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

The Python guard requires Python 3.11+ and checks whitespace, XML/TOML syntax,
wrapper integrity and a limited set of recognizable credential patterns. It is
not a complete Kotlin formatter or comprehensive secret scanner. Android lint
provides the initial Android static analysis. JVM test dependencies are ready;
there are no JVM business-logic tests until Step 2 introduces the financial model.

Navigation and activity-recreation tests are in `src/androidTest/`. With a running
Android emulator or connected test device, run:

```bash
./gradlew :apps:android:connectedDebugAndroidTest
./gradlew :apps:android:installDebug
adb shell am start -n com.financegenie.android.debug/com.financegenie.android.MainActivity
```

Manually check dark mode, rotation, back navigation, 200% font size and TalkBack.
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
| Compile / target / minimum SDK | 34 / 34 / 26 |

These versions match the installed SDK and cached dependencies available for this
private foundation. They are not presented as the latest releases. Upgrade the
toolchain and target SDK together before public distribution; see
[the decision record](docs/decisions.md).

## Data and project boundaries

The app currently contains no financial records, network integration or sensitive
permission request. Cloud sync, notification access and AI are not configured.
OS cloud/device-transfer backup exclusions are explicit; actual device behavior
must still be verified. The app has no application-level database encryption or
portable backup yet. Never commit real financial examples, local configuration,
signing keys or service credentials. Use synthetic data for later tests.

- [Product requirements](docs/product-requirements.md)
- [Decisions](docs/decisions.md)
- [Threat model](docs/security-threat-model.md)
- [Verification log](docs/verification-log.md)
- [Acceptance checklist](docs/e2e-acceptance.md)

Licensed under [Apache License 2.0](LICENSE).
