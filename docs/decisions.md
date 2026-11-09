# Decisions

## 2026-11-09 — Basic Android foundation

- **Scope:** The user's request is project setup. Create the Step 1 foundation and
  supporting Step 0 records; do not automatically build the rest of milestone A.
- **Structure:** One app module, `:apps:android`, under `apps/android/`. Kotlin
  packages separate UI/navigation/theme; domain/data packages arrive with real
  functionality. Compose and Material 3, with no DI framework or backend yet.
- **Application ID:** `com.financegenie.android`; debug adds `.debug` and the
  visible name FinanceGenie Dev. Confirm final identifier before distribution.
- **Toolchain:** JDK 17, Gradle 8.9, AGP 8.6.0, Kotlin/Compose compiler 2.0.20,
  Compose BOM 2024.09.00, compile/target SDK 34 and minimum SDK 26. This installed,
  cached stack permits offline dependency resolution in this environment. It is
  a temporary private-development baseline, not a latest-version or Play-readiness
  claim. AGP 8.6 requires Gradle 8.7+ and JDK 17. Kotlin documents support through
  Gradle 8.8 and permits newer releases with possible compatibility warnings;
  This combination was then compiled and linted successfully, including a fresh
  source-copy offline build. Upgrade the toolchain/SDK as a tested set before
  public distribution.
- **Wrapper:** Standard Gradle 8.9 scripts/JAR, with JAR hash checked against the
  official reference and distribution hash recorded in wrapper properties.
- **Privacy:** No runtime permission prompts, network SDK, analytics or real data.
  Disable OS backup and explicitly exclude cloud/device-transfer domains. Runtime
  behavior is a device verification item; portable recovery arrives in Step 8.
- **Checks:** Android lint plus an initial whitespace/XML/TOML/wrapper/recognized
  credential guard. This is not a full Kotlin formatter or comprehensive secret
  scanner. UI navigation/recreation tests require a device; JVM domain tests arrive
  with Step 2. Do not create trivial tests just to make a test count nonzero.
- **Verification limits:** The sandbox initially blocked Gradle's local socket and
  automatic review briefly reported a usage limit. A retry was approved; debug,
  unsigned release and instrumentation APK builds passed, with no Android lint
  issues. ADB also ran successfully and reported no connected devices. AVD,
  emulator binary/system images and `/dev/kvm` are absent, so actual launch/UI,
  accessibility and device-backup behavior remain unverified.
- **Build cache:** Renaming the adaptive-icon resource exposed stale resource
  outputs in the build cache. A clean build with `--no-build-cache` resolved the
  failure; the independent fresh-source build also disabled this cache. The final
  icon includes monochrome support and produces no lint warnings.

Sources reviewed 9 November 2026:

- [AGP 8.6 compatibility](https://developer.android.com/build/releases/agp-8-6-0-release-notes)
- [Kotlin 2.0.20 Gradle support](https://kotlinlang.org/docs/whatsnew2020.html#gradle)
- [Compose compiler plugin for Kotlin 2+](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
- [Gradle distribution/wrapper checksums](https://gradle.org/release-checksums/)

The plan's accounting/date/refund rules remain the defaults for later steps. The
reference performance device and future parser/cloud providers are unresolved
until their corresponding verification/integration steps.
