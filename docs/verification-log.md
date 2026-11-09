# Verification log

Entries retain the conditions/results at the time they were recorded. Later
follow-ups supersede earlier pending statuses; current status and step tradeoffs
are maintained in [progress_tracker.md](../progress_tracker.md).

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

## Step 1 — Physical-phone verification follow-up — 2026-11-09

- **Environment:** User-provided A063, Android 15 / API 35. Serial stored only in a
  private temporary file, never this repository. ADB used the isolated local socket
  at `/tmp/financegenie-adb.sock` and Android user directory from the setup checks.
- **Scope:** Existing development application and its navigation test APK. No
  app/device data clearing or device-wide preference changes were performed.
- **Commands:** ADB selected the connected test device; its serial argument is
  deliberately redacted below.

  ```text
  adb -s <selected-test-device> install -r -t apps/android/build/outputs/apk/debug/android-debug.apk
  adb -s <selected-test-device> install -r -t apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
  adb -s <selected-test-device> shell am instrument -w -r com.financegenie.android.debug.test/androidx.test.runner.AndroidJUnitRunner
  adb -s <selected-test-device> shell am start -W -n com.financegenie.android.debug/com.financegenie.android.MainActivity
  ```

- **Result:** Both APK installs returned Success. Instrumentation returned
  **OK (2 tests)** in 3.926 seconds: all four top-level tabs reachable and selected
  destination retained after activity recreation. Launch returned **Status: ok**,
  **LaunchState: COLD**, TotalTime 862 ms. This single observation is not a p95
  performance benchmark.
- **Manual evidence:** Confirmed FinanceGenie was the foreground activity before
  capturing its screen. A UI-idle screenshot was visually inspected; the user
  separately confirmed **“Looks good; all four tabs work”** and readable text.
- **Evidence:** `/tmp/financegenie-phone-foundation-tests.log`,
  `/tmp/financegenie-phone-foundation-stable.png`. These are app-only temporary
  evidence, not committed personal device screenshots/logs.
- **Remaining checks:** Full dark/large-font/TalkBack checks belong to Step 4/Gate A;
  actual backup/transfer behavior remains a later data-lifecycle check. No claim
  of complete accessibility, performance or Gate A acceptance is made here.
- **Status:** PASS for Step 1 foundation checks, superseding its earlier
  device-unavailable status. Existing build/permission/wrapper evidence still applies.
- **Next action:** Step 2's pure financial model; new milestone A implementation
  request supersedes the prior setup-only scope boundary.

## Step 2 — Pure financial model and golden fixtures — 2026-11-09

- **Implemented:** `domain/` exact money parsing/serialization, ledger-local dates,
  account/category/committed-event/budget/observation models, snapshot and atomic
  edit validation, financial reports, budget remaining and statement reconciliation.
  Shared synthetic JSON under `fixtures/ledger/`; 35 JVM tests. Fixture directory
  is supplied by Gradle, independent of test working directory. Gson is test-only.
- **Decisions/tradeoffs:** Recorded in `progress_tracker.md` and `decisions.md`:
  pure economic model first, snapshot validation for clarity, exact `BigInteger`
  accumulation with checked `Long` output, separate input ceilings and transport
  ranges, no UI/persistence/audit scaffolding early. No financial contract changes.
- **Actual command:**

  ```bash
  GRADLE_USER_HOME=/tmp/financegenie-gradle-home ANDROID_USER_HOME=/tmp/financegenie-android-user ./gradlew --offline --no-daemon --max-workers=2 :apps:android:testDebugUnitTest :apps:android:assembleDebug :apps:android:lintDebug :apps:android:assembleDebugAndroidTest
  ```

- **Final result:** **BUILD SUCCESSFUL in 21s**, 76 actionable tasks (25 executed,
  one from cache, 50 up-to-date). JVM XML reports record **35 tests, zero failures,
  zero errors**: MoneyTest 7, LedgerCalculatorTest 15, LedgerValidatorTest 11,
  GoldenFixtureTest 2. Android lint reports **No issues found**. Debug and Android
  instrumentation APKs compile successfully. No Android runtime is needed for
  these financial tests.
- **Coverage/evidence:** Exact six-event golden fixture, every stored expected KPI,
  category/day/month totals, account positions, independent category/overall caps
  and 25 ledger permutations; transfers, card overpayment, partial/cross-month
  refunds, explicit unlinked refunds, archive selection/history, deletion/undo,
  adjustment/observation semantics, future/backdated dates, leap day, India/UTC
  boundary, clock-zone changes, input ceilings and signed 64-bit overflow.
- **Failure resolved:** The first JVM run passed, but lint flagged five uses of
  `BigInteger.longValueExact()` as requiring API 31. Replaced them with explicit
  signed-64-bit bounds plus `toLong()`, preserving API 26 and overflow rejection.
  Reran the relevant suite/build/lint successfully; no suppression/baseline added.
- **Repository checks:** `python3 scripts/check_repository.py` and
  `git diff --check` passed. Hygiene checks now validate JSON syntax as well as
  XML/TOML, so shared fixture corruption is caught in CI. Fixtures contain only
  synthetic stable IDs/names and integer-string money; no phone serial or private data.
- **Artifacts:** `/tmp/financegenie-step2-checks.log`,
  `apps/android/build/test-results/testDebugUnitTest/TEST-*.xml`,
  `apps/android/build/reports/tests/testDebugUnitTest/index.html`,
  `apps/android/build/reports/lint-results-debug.html`.
- **Limitations:** Calculations are not yet wired into screens or a database.
  Performance targets are not yet benchmarked; neither Gate A nor storage/recovery
  verification is complete. Existing user-visible UI remains the foundation.
- **Status:** PASS for Step 2.
- **Next action:** Step 3 Room schema, repositories, atomic edits and physical-phone
  storage/reopen verification. Update the tracker and tradeoffs as that work proceeds.

## Step 3 — Room persistence and atomic operations — 2026-11-09

- **Implemented:** Ten-table schema v1; UUID/calendar/instant converters; integer
  money; foreign keys/indexes; normalized provenance and batch bookkeeping;
  revision-checked account/category/budget/observation/event repositories;
  transactional event groups and undo; consistent Flow snapshots; bounded,
  deterministic date/account/category/search queries. No UI wiring, cloud or
  notification collection was added. See `local-data.md` for the API boundary.
- **Build command:**

  ```bash
  GRADLE_USER_HOME=/tmp/financegenie-gradle-home ANDROID_USER_HOME=/tmp/financegenie-android-user ./gradlew --no-daemon --max-workers=2 :apps:android:testDebugUnitTest :apps:android:assembleDebug :apps:android:lintDebug :apps:android:assembleDebugAndroidTest :apps:android:assembleRelease
  ```

- **Build result:** PASS — **BUILD SUCCESSFUL in 52s**; 128 actionable tasks,
  77 executed, five from cache, 46 up-to-date. All **35 financial JVM tests pass**
  without failures/errors. Debug, unsigned release and instrumentation APKs build;
  lint reports **No issues found**. One initial offline run needed an uncached
  transitive coroutine-test JAR; the dependency was downloaded from its public
  repository and the complete command above passed.
- **Schema verification:** Room plugin exported
  `apps/android/schemas/com.financegenie.android.data.local.FinanceGenieDatabase/1.json`.
  Parsed JSON assertions confirmed version 1, ten tables, INTEGER money fields,
  unique `(month, scope_key)` budget index and no raw-message-body column in the
  primary transaction table. This is initial schema creation/reopen, not an upgrade
  migration. Future real upgrades require populated-fixture preservation tests.
- **Phone command:**

  ```bash
  ANDROID_USER_HOME=/tmp/financegenie-android-user ADB_SERVER_SOCKET=localfilesystem:/tmp/financegenie-adb.sock python3 -u scripts/verify_storage_on_device.py
  ```

- **Phone results:** PASS on the user's A063 / Android 15 / API 35. Installs used
  `-r -t`; the storage package returned **OK (17 tests)** in 0.783 seconds: 15 Room
  repository/DAO cases plus two persistence seed/reopen cases. The separate probe
  then ran seed-only instrumentation (**OK (1 test)**), force-stopped only
  `com.financegenie.android.debug`, and ran verify-only instrumentation with
  `requireFreshProcess=true` (**OK (1 test)**). The reader asserted a different
  process ID and the same local ledger ID/revision, one-paise event, notes,
  provenance and exact report. Test-owned files/preferences were removed afterward.
- **Coverage:** Exact small/large/signed values and nanosecond event timestamps;
  duplicate IDs and source keys; stale/concurrent edits; soft delete/undo and stale
  undo rejection; out-of-order linked groups and invalid-edit rollback; foreign-key
  history protection; archive behavior; nullable overall-budget uniqueness;
  paging, half-open date/account/category filters and literal wildcard search;
  transactional Flow state; shared golden totals after DB round-trip; real file
  reopen and process restart. No test uses or clears the production ledger.
- **Launch:** PASS — driver relaunched FinanceGenie Dev with **Status: ok**,
  TotalTime 837 ms (a single observation, not a performance benchmark).
- **Privacy/artifacts:** Debug and release APK inspection confirmed no internet or
  sensitive permission, no bundled baseline financial fixture, unchanged backup
  disablement and a non-exported Room service. Only the AndroidX signature-scoped
  internal permission is present. The phone log was checked for absence of its
  serial; no device content or real financial record is committed.
- **Repository/CI:** `python3 scripts/check_repository.py`, `git diff --check` and
  schema JSON assertions pass. CI now verifies exported schema changes are
  committed; this workflow change has not been run remotely in the current work.
  Generator formatting/expected embedded-payload warnings are narrowly handled
  as documented in `decisions.md`, without suppressing live schema validation.
- **Evidence:** `/tmp/financegenie-step3-checks.log`,
  `/tmp/financegenie-step3-phone.log`, generated schema v1, JVM reports under
  `apps/android/build/test-results/testDebugUnitTest/`, lint reports and
  `scripts/verify_storage_on_device.py` for reproducibility.
- **Limitations:** The UI still uses foundation screens. Full data entry/management,
  app lock, performance benchmarks and backup/restore remain their planned steps.
  This database is app-private, not claimed to be application-encrypted. No
  migration path is falsely claimed before a real version upgrade exists.
- **Status:** PASS for Step 3.
- **Next action:** Step 4 account/category management and manual transaction
  create/edit/delete/undo wired to this repository, verified on the phone.

## Step 4 — Manual entry, management and privacy — 2026-11-09

- **Implemented:** Application-scoped repository; lifecycle-aware ViewModels;
  account/category create/edit/archive/unarchive; opening positions and statement
  observations; manual expense/income/transfer/refund/adjustment forms; exact basic
  Overview totals; search/date/account/category/deleted filters; original source
  labels; soft deletion, grouped linked-refund deletion, undo and restoration.
  System device-credential app lock, configurable timeout and default screenshot/
  preview protection are wired without new sensitive permissions or a schema change.
- **Build/check command:**

  ```bash
  GRADLE_USER_HOME=/tmp/financegenie-gradle-home ANDROID_USER_HOME=/tmp/financegenie-android-user ./gradlew --offline --no-daemon --max-workers=2 :apps:android:testDebugUnitTest :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest :apps:android:lintDebug :apps:android:assembleRelease
  ```

- **Automated build results:** Final combined command passes in 9 seconds (128
  tasks: five executed, 123 up-to-date). 45 JVM tests pass with zero failures/errors/skips:
  the existing 35 financial cases and ten lock-session cases. Debug, unsigned
  release and instrumentation APKs build, and Android lint reports no issues.
  Repository hygiene and `git diff --check` pass. Packaged debug/release permissions
  contain no network/sensitive permission or bundled financial fixture.
- **Phone command:**

  ```bash
  ADB_SERVER_SOCKET=localfilesystem:/tmp/financegenie-adb.sock python3 scripts/verify_storage_on_device.py --ui-only
  ```

- **Phone results:** PASS on A063 / Android 15 / API 35 — **OK (12 tests)** in
  77.931 seconds: two navigation/recreation, one account/category management,
  five financial-entry, two privacy/session/flag, one large-font/theme and one
  failure/retry test. The initial combined `--ui` run separately passed all 17
  storage tests and the storage fresh-process seed/verify stages before UI fixes.
  Storage schema/operations were unchanged by these UI fixes.
- **CRUD coverage:** ₹249.50 Food expense → edit to ₹199.50 → rotate while editing
  → save → delete/undo → delete/restore → search/account/category filters → recreate.
  Missing account, excess decimals, future date, stale revision and same-frame
  double-save are tested. Account and category edits retain their archive state;
  statement observations preserve a negative card position; card debt has the
  **Amount owed** label. Unlinked refunds require explicit confirmation; archived
  choices cannot be newly selected, while historical references remain editable.
- **Golden journey:** The test reads the shared fixture's six event kinds, dates,
  amounts, accounts, categories and refund link, submitting each through the UI.
  Independent expectations confirm net spending 349,900 paise, income 6,000,000,
  Bank 5,550,100, Cash 100,000 and Card 0. An adjustment changes only the relevant
  position. Reducing the linked purchase below refunded value is rejected, and
  purchase/refund deletion plus undo is atomic. Manual corrections preserve CSV
  origin labels; no CSV ingestion UI is introduced before Step 7.
- **Fresh-process UI read:** Seed-only instrumentation records a ₹199.50 expense
  through the UI (**OK (1 test)**, 7.055s). The driver force-stops only FinanceGenie
  Dev, then reader-only instrumentation asserts a different process and the same
  ₹199.50 Overview value/record (**OK (1 test)**, 2.954s). Probe-owned files are
  removed afterward. App launch returns **Status: ok**, cold TotalTime 1053ms
  (one observation, not a benchmark).
- **Privacy automation:** Cold lock screen excludes financial navigation semantics;
  injected test-only grant/cancellation, immediate timeout, background hiding and
  rotation/session retention pass. Tests inspect actual window `FLAG_SECURE` at
  default, after explicit sharing enablement, and after locking. A failed read
  leaves Settings available and retry genuinely restarts repository observation.
  These session-policy seams do not claim to verify an Android device credential.
- **Accessibility/layout:** Compose-local font scale 2.0 in dark mode permits all
  four tabs, selection, a multi-field adjustment form and Save. The test then
  renders light mode at scale 1.0. Synthetic captures under the test app cache were
  retrieved to `/tmp` and visually inspected; no image or phone serial is committed.
  Real TalkBack and global/OEM accessibility behavior remain manual checks.
- **Resolved findings:** Fixed a deferred state read that prevented immediate
  window-policy updates. Undo confirmations now have an explicit finite timeout.
  UI tests dismiss transient confirmations and close the keyboard before subsequent
  row/save actions, and assert actual data/UI completion rather than sleeping.
  An erroneous test import, a text-field label/value assertion and test-only
  StateFlow composition access were corrected; the final suite passes unchanged
  financial expectations and without skipped mandatory cases.
- **Isolation/evidence:** All phone tests inject in-memory/test-named storage and
  dedicated preference files before launching the activity. No `pm clear`, real
  ledger wipe, device-setting change or broad device log/screenshot is used.
  Evidence is `/tmp/financegenie-step4-final-checks.log`,
  `/tmp/financegenie-step4-phone.log` (storage results),
  `/tmp/financegenie-step4-phone-ui-final.log`, JVM/lint reports and repository
  test/driver sources. These results were collected locally; remote CI was not
  part of this verification run.
- **Mandatory manual checks:** PASS — the user explicitly confirmed both requested
  check groups on 9 November 2026: real Android device-credential enablement,
  Lock now, cancelled authentication staying protected, successful unlock and
  returning after 30 seconds requiring authentication; protected screenshots/
  recent-app previews and usable TalkBack labels/controls across tabs and a form.
  These results are user-reported on the A063, separate from automated policy
  tests. Only the user entered the credential or changed accessibility settings.
- **Status:** Step 4 PASS, including automated and manual acceptance. Gate A is
  not passed. Calendar-budget management,
  charts/performance, portable recovery and actual backup behavior retain their
  later planned gates.

## Step 5 — Calendar budgets — 2026-11-09

- **Implemented:** Overall/category monthly limits, create/edit/remove, shared
  past/future month navigation, expenses/refunds/net/remaining/over-by text,
  80%/at-limit/overspent in-app warnings, and explicit atomic next-month copying.
  Existing target caps and source months are preserved; new records receive new
  IDs, archived sources are skipped, and a ledger-revision check protects previews.
  Monthly scope identity is fixed during edits. Zero remains a real limit.
- **Build command:**

  ```bash
  GRADLE_USER_HOME=/tmp/financegenie-gradle-home ANDROID_USER_HOME=/tmp/financegenie-android-user ./gradlew --offline --no-daemon --max-workers=2 :apps:android:testDebugUnitTest :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest :apps:android:lintDebug :apps:android:assembleRelease
  ```

- **Build results:** PASS — full command **BUILD SUCCESSFUL in 39s**, 128 tasks
  (46 executed, 82 up-to-date). **55 JVM tests** pass with zero failures/errors/skips,
  including ten new budget-planning/report/warning cases. Debug, unsigned release
  and test APKs build; lint has **No issues found**. After test synchronization
  updates, test APK/lint build also passes in 20s (56 tasks). No application source
  changed after the full build/domain run.
- **Phone storage:** The combined `--ui` driver first passed **OK (23 tests)** in
  1.000s on A063 / Android 15 / API 35, followed by the two explicit storage
  process-restart stages (**OK (1 test)** each). Six new repository tests verify
  copy counts/fresh IDs, source and existing-target metadata preservation,
  rollback after an injected failure on the second insert, stale preview rejection,
  archived/no-op behavior, immutable scope and revision-checked removal while
  preserving transactions and other months. Schema v1 and its exported JSON are
  unchanged; this is not a migration.
- **Budget UI command:**

  ```bash
  ADB_SERVER_SOCKET=localfilesystem:/tmp/financegenie-adb.sock python3 scripts/verify_storage_on_device.py --budget-ui-only
  ```

- **Budget UI:** PASS — **OK (5 tests)** in 43.619s. Tests enter independent
  overall/Food limits and ₹4,000/₹1,500 expenses, verify ₹1,000 remaining then
  ₹500 over, change only the overall cap, and recreate the activity. Other cases
  cover invalid month/negative limit, zero/no budget, duplicate and same-frame save,
  removal, future-month copy preserving an existing ₹9,000 target, new IDs and
  unchanged source metadata, dialog recreation, and stale copy/edit errors.
- **Historical UI journey:** A November ₹4,000 purchase and bank/cash transfer
  leave ₹1,000 from a ₹5,000 cap. A December ₹1,000 refund shows ₹0 expenses,
  ₹1,000 refunds, −₹1,000 net and ₹6,000 remaining. Moving/correcting the purchase
  to December ₹5,500 restores November remaining to ₹5,000 and leaves December
  ₹4,500 net/₹500 remaining. UI and domain expectations agree without rollover.
- **UI process restart:** Seed instrumentation records a ₹199.50 expense and ₹500
  overall budget through the forms (**OK (1 test)**, 9.297s). After force-stopping
  only FinanceGenie Dev, reader instrumentation asserts a different process and
  the same ₹500 limit/₹300.50 remaining plus exact stored values (**OK (1 test)**,
  3.701s). Probe-owned DB/preferences are removed. App launch returns **Status: ok**,
  TotalTime 862ms (one observation, not a benchmark).
- **Regression:** The first broader UI run found test assumptions about immediate
  background-flow completion and the destination after unlocking. Filter tests
  now wait for the specified row absence/presence; lock tests require a resumed
  activity, verify the financial gate opens and explicitly navigate to Overview.
  No production lock policy or expected financial result was relaxed. The final
  navigation/entry/privacy/error/layout rerun passes **OK (12 tests)** in 81.150s,
  with no skipped cases. It launches the development app successfully afterward.
- **Layout:** The regression includes entering a budget at Compose-local 200% font
  in dark mode. Its synthetic budget screenshot was retrieved from the test app
  cache to `/tmp/financegenie-step5-font200-dark-budget.png` and visually inspected:
  readable amounts and reachable edit/remove actions, with scrolling. Existing
  form navigation/light-theme checks also pass. This does not claim a new human
  budget-specific TalkBack run; that remains in the full-app Gate A walkthrough.
- **Isolation/privacy:** Tests use only their injected in-memory/test-named DBs and
  dedicated preferences, never the normal ledger. There is no `pm clear`, personal
  screenshot, global accessibility change, notification permission or background
  alert scheduling. PIN entry is untouched. Copied/removed limits are configuration;
  financial transaction history is preserved. Repo hygiene and diff checks pass.
- **Evidence:** `/tmp/financegenie-step5-checks.log`,
  `/tmp/financegenie-step5-phone.log` (storage),
  `/tmp/financegenie-step5-budget-phone.log`,
  `/tmp/financegenie-step5-regression-build.log`,
  `/tmp/financegenie-step5-regression-phone.log`, JVM/lint reports and repository
  tests/driver. `--ui` reproduces storage, regression, budget and restart checks;
  `--budget-ui-only` runs the budget subset/probe. Remote CI is separate from these
  local/device results.
- **Status:** Step 5 PASS. Next is Step 6 offline analytics and measured performance.
  Gate A remains outstanding until its other features and recovery/accessibility/
  backup acceptance checks are complete.
