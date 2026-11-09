# Verification log

## Step 0 — Foundation requirements and decisions — 2026-11-09

- **Implemented:** Product requirements, initial decisions, threat model and
  acceptance checklist. Canonical rules remain in `plan.md`; current scope is
  basic project setup only.
- **Assumptions:** API 26 minimum, private build, INR/Asia/Kolkata, calendar-month
  budgets and refund-date reporting. Physical test phone is not yet selected.
- **Automated checks:** Python integer-paise calculation independently confirmed
  the golden fixture: Bank 5,550,100; Cash 100,000; Card 0; net spending 349,900;
  total position 5,650,100; Groceries budget remaining 300,000. Assertion exit 0.
- **Manual document checks:** Traced all six plan journeys to steps/deferred scope;
  confirmed no bank credentials/payment instructions, no unresolved financial
  semantics needed for setup, and no optional integration prerequisite for A.
- **Security/privacy:** Initial controls distinguish current source configuration
  from future runtime/device guarantees. Real financial data is excluded.
- **Evidence:** `product-requirements.md`, `decisions.md`,
  `security-threat-model.md`, `e2e-acceptance.md`; integer assertions run locally.
- **Known limitations:** Reference performance device and future provider choices
  remain deferred to their actual verification/integration steps.
- **Status:** PASS for this documentation gate.
- **Next action:** Step 1 project setup and build verification.

## Step 1 — Basic Android project — 2026-11-09

- **Implemented:** Root Gradle wrapper/catalog, single Compose app module,
  four-tab navigation, light/dark themes, string resources, adaptive launcher
  icon, separate debug ID/name, explicit backup exclusions, CI and development
  instructions. Two instrumented navigation/recreation checks are included.
- **Environment:** Linux x86_64; Temurin JDK 17.0.21; Android SDK Platform 34 /
  Build Tools 34.0.0. Gradle/dependency cache copied into
  `/tmp/financegenie-gradle-home`; Android user files isolated under
  `/tmp/financegenie-android-user`. `local.properties` is ignored.
- **Automated checks:**
  - `python3 scripts/check_repository.py` — PASS: whitespace/XML/TOML syntax,
    recognized credential patterns and official wrapper-JAR checksum.
  - `sh -n gradlew` and `unzip -t gradle/wrapper/gradle-wrapper.jar` — PASS.
  - `git diff --check` — PASS for tracked changes; hygiene guard also scanned
    untracked project files.
  - Python static checks — PASS: pinned direct dependency versions exist in the
    available cache; source manifest has no permission requests; legacy and modern
    backup exclusions cover all selected storage domains.
  - Python/PyYAML parse of `.github/workflows/android.yml` — PASS; repository
    permission is `contents: read`. Remote CI has not been run.
  - Publication preparation — PASS: `git check-ignore --no-index --stdin`
    assertions covered 33 generated/private paths and 11 retained source/fixture
    paths. Gradle caches, local SDK configuration, signing material, credentials
    and private exports are excluded; the wrapper JAR remains versioned.
    `.gitattributes` normalizes source text, preserves Windows batch-file line
    endings and treats JAR files as binary.
  - Final Gradle command below — PASS, `BUILD SUCCESSFUL in 26s`, 120 actionable
    tasks (119 executed, one up-to-date). Debug, unsigned release and Android test
    APKs were produced. Android lint reported **No issues found**.
  - `testDebugUnitTest` — NO-SOURCE; no JVM cases were run. Two navigation/activity
    recreation instrumentation cases compiled but were not executed.
  - Fresh-source offline build — PASS, `BUILD SUCCESSFUL in 15s`, 35 tasks executed.
    The copy at `/tmp/financegenie-clean-final` was made from non-ignored source
    files, without `local.properties`, project Gradle state or generated outputs.
    It reused only dependency/distribution caches; build-output caching was disabled.
- **Final build command:**

  ```bash
  GRADLE_USER_HOME=/tmp/financegenie-gradle-home ANDROID_USER_HOME=/tmp/financegenie-android-user ./gradlew --offline --no-daemon --no-build-cache --max-workers=2 :apps:android:clean :apps:android:assembleDebug :apps:android:testDebugUnitTest :apps:android:lintDebug :apps:android:assembleDebugAndroidTest :apps:android:assembleRelease
  ```

- **Fresh-source command** (working directory `/tmp/financegenie-clean-final`):

  ```bash
  ANDROID_HOME=/home/nikhil-more/Android/Sdk GRADLE_USER_HOME=/tmp/financegenie-gradle-home ANDROID_USER_HOME=/tmp/financegenie-android-user ./gradlew --offline --no-daemon --no-build-cache --max-workers=2 :apps:android:assembleDebug
  ```

- **Access history:** Initial sandbox execution failed before project evaluation
  because the Gradle daemon could not open its local socket. Automatic approval
  review initially failed with a reviewer usage limit; after the user's request
  to continue, a retry was approved. First offline resolution exposed missing
  transitive dependencies; a network-enabled build downloaded public Maven
  artifacts and passed. After icon-warning corrections, resource linking failed
  using stale build-cache paths; the final clean build disabled that cache and
  passed. These intermediate failures were resolved before the results above.
- **Manual/device checks:** NOT RUN. No emulator binary, AVD/system image or KVM
  device is installed. `ANDROID_USER_HOME=/tmp/financegenie-android-user
  ADB_SERVER_SOCKET=localfilesystem:/tmp/financegenie-adb.sock adb devices -l`
  succeeded with approved local-socket access and returned an empty device list.
- **Security/privacy:** Inspected debug/release APKs with SDK 34 `aapt dump
  permissions`, `aapt2 dump resources` and `aapt dump xmltree`. Both contain only
  the AndroidX app-scoped internal receiver permission, declared signature-only;
  no internet/SMS/storage/notification-access permission. Both merged manifests
  retain `allowBackup=false`; packaged legacy XML has nine exclusion entries,
  modern XML has 18 across cloud/device-transfer sections. Optimized release XML
  paths were resolved from its resource table. `apksigner verify` passed for the
  development APK. Actual device backup/transfer and runtime logs remain untested.
- **Evidence:** `/tmp/financegenie-final-build.log`,
  `/tmp/financegenie-clean-build.log`,
  `apps/android/build/reports/lint-results-debug.html`, and APKs under
  `apps/android/build/outputs/apk/`. No runtime screenshots or launch evidence.
- **Known limitations:** No financial persistence/business logic yet; JVM test
  folder is ready but contains no cases. Instrumented tests need a device. Target
  SDK/toolchain must be upgraded before public distribution. The older AGP reports
  an SDK metadata-version warning, and native path-library symbols cannot be
  stripped without additional tooling; these are build warnings, not lint issues.
  Remote GitHub Actions CI is configured but has not been triggered.
- **Status:** BLOCKED only on mandatory device launch/navigation/accessibility
  verification. Project creation, compilation, lint and artifact checks are complete.
- **Next action:** Connect an API 26+ test device or start an emulator, run
  `:apps:android:connectedDebugAndroidTest`, install/launch and perform the manual
  checks in `e2e-acceptance.md`. Stop at this setup scope until more work is requested.
