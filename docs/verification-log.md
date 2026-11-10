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

## Step 6 — Offline analytics and performance baseline — 2026-11-09

- **Implemented:** Scoped gross/net spending, received refunds, income and recorded
  surplus; current estimated tracked positions; signed category bars, bounded
  daily/six-month trends and exact text alternatives; five recent matching events
  with source/edit controls and navigation to the matching feed. Overview and
  Transactions share literal search, account/category and month/all-date filters.
  Budgets retain complete-month/all-account scope; current positions retain all
  recorded history through today. Deleted rows never enter analytics.
- **Financial boundary:** Validate the complete ledger before applying selection,
  so a filtered refund can still reference an out-of-scope purchase. Batched
  reports share validation and positions by as-of date. Money labels remain exact
  integer paise; only chart geometry uses floating-point fractions. Empty days/
  months are zero-filled; refund-heavy categories can extend below zero.
- **Build:** Full debug/release/test/benchmark app and driver checks pass:
  **BUILD SUCCESSFUL in 53s**, 205 tasks (43 executed, 162 up-to-date). The final
  debug test/lint build passes in **21s**, 56 tasks; benchmark instrumentation in
  **16s**, 47 tasks; the updated startup driver in **9s**, 29 tasks. **66 JVM
  tests** pass with zero failures/errors/skips. Lint reports **No issues found**.
  Exported Room schema v1 is unchanged. This is not a migration.
- **Phone correctness:** Final `env -u ADB_SERVER_SOCKET python3
  scripts/verify_storage_on_device.py --ui` run passes **27 storage tests**
  (1.155s), **12 entry/navigation/privacy/error/layout tests** (74.967s), **five
  budget tests** (40.149s), and **six analytics tests** (25.852s). Separate storage
  and expense/budget save/force-stop/read stages pass, enforcing fresh reader
  processes. The final normal-app launch reports **Status: ok**. The default ADB
  server had the authorized phone; no reconnection or server reset was required.
- **Analytics cases:** Golden totals and signed category/daily/monthly values;
  literal merchant/notes search, account/category/date intersections, both transfer
  accounts, budget independence, recent editing and cross-surface convergence;
  no accounts/no events, one-paise refund then deletion, and 1,000 one-paise events
  with an exact ₹10 total. JVM cases also cover leap day, earliest supported month,
  all-history totals with bounded chart windows and archived long labels.
- **Cache correctness:** Reuse one snapshot only when ledger settings and SQLite
  connection/cross-connection counters match. Added cross-repository invalidation
  and three outer-rollback/reused-revision cases, including dispatcher switching
  and a second file-backed DB connection. They all pass on the phone. This closes
  a defect found during review of a revision-only cache: a rolled-back revision
  could otherwise hide different data committed later at the same revision.
  Atomic writes, provenance, full validation and stale-edit rules remain intact.
- **Test synchronization:** The rotation test asserts both the accepted text field
  and retained ViewModel draft before recreation. Its earlier failure occurred
  without that input-completion precondition; the final unchanged ₹199.50
  expectation passes. Analytics tests use the fixture's actual hexadecimal UUIDs
  and wait for the requested month/report before asserting. No financial expected
  value, lock policy or mandatory test is relaxed or skipped.
- **Layout:** The synthetic 200% font/dark analytics capture was retrieved from
  the test-owned app cache and visually inspected. Archived long labels wrap,
  exact positive/negative amounts and zero baselines remain available with
  scrolling. Daily values and all monthly/category amounts have text alternatives.
  This does not claim a new human analytics-specific TalkBack run; the full-app
  walkthrough remains part of Gate A.
- **Performance build:** Final isolated app/driver/test compilation passes in
  **23s**, 106 tasks (16 executed, 90 up-to-date). Benchmark-only lifecycle and
  committed-frame signals are excluded from normal apps. The runner verifies
  installed-artifact hashes and complete sample counts; a completed report is
  preserved by requiring a fresh output directory on later runs.
- **Performance results:** The [aggregate baseline](performance-baseline.json)
  records **20 process-cold launches** and **30 samples per operation** on the
  non-debuggable/profileable A063 build with 10,000 synthetic records. P95 values:
  confirmed Overview **2,278.64ms**, committed-frame diagnostic **632.66ms**,
  monthly summary including an uncached snapshot read **187.30ms**, 50-row search
  **2.99ms**, expense Save-to-visible **278.76ms**, warm revision-checked read
  **1.79ms**. Summary/search/expense-save targets pass; the conservative confirmed
  startup endpoint exceeds its 2,000ms target. It includes process creation,
  frame commit, broadcast delivery and exact UI/PID/foreground verification.
  Frame commit is buffer submission, not guaranteed display presentation; its
  shorter time is not substituted for the failed confirmation result.
- **Trace limitation:** Two Macrobenchmark 1.3.4 attempts yielded 13/20 startup
  samples. Compatible 1.4.1 fixes truncated process-name matching, restoring frame
  groups, but TTID/TTFD still remains 14/20 even after all twenty launches pass a
  nonce-correlated fully-drawn/frame-commit barrier. These results remain
  **INCOMPLETE**, recorded in the [trace diagnostic](performance-trace-diagnostic.json).
  The direct readiness method independently measures all twenty launches from
  before the Activity request to the committed frame and later UI confirmation;
  it does not fill or rename missing trace timings. Startup instrumentation and
  budget-save latency remain Gate A checks. No timing target is relaxed.
- **Other baselines:** APK **7,163,426 bytes**, maximum sampled process PSS
  **268,819 KiB**, without claiming a continuous absolute peak. The earlier trace
  diagnostic identifies its own artifact and 505 captured frame samples: CPU
  duration P95 **74.96ms**, overrun P95 **130.89ms**. Capture coverage is incomplete;
  this is not a universal frame/smoothness pass.
- **Isolation:** Benchmark target is separately packaged, non-debuggable and
  profileable, with fixed synthetic data and a same-signature setup permission.
  Debug/release APK inspection confirms benchmark classes/setup/data are absent;
  normal permissions remain the AndroidX internal receiver permission only.
  No real ledger wipe, credential access, personal capture, broad phone log or
  global accessibility change is used. Raw traces and device serials stay out of
  version control; `.gitignore` now excludes Perfetto/library report artifacts.
  After retaining one test-owned trace locally, the three synthetic benchmark
  packages were uninstalled and FinanceGenie Dev reopened with its data retained.
- **Evidence:** `/tmp/financegenie-step6-verified-build.log`,
  `/tmp/financegenie-step6-rollback-tests-build.log`,
  `/tmp/financegenie-step6-verified-performance-test-build.log`,
  `/tmp/financegenie-step6-verified-driver-build.log`,
  `/tmp/financegenie-step6-verified-phone.log`,
  `/tmp/financegenie-step6-readiness-build.log`,
  `/tmp/financegenie-step6-readiness-performance-run.log`, aggregate JSON reports,
  JVM/lint reports and checked-in
  tests/drivers. These are local/device results; remote CI is separate.
- **Status:** Step 6 PASS for accurate accessible analytics and recorded baseline.
  Startup confirmation/trace limitations and budget-save timing are explicit open
  Gate A checks. CSV is Step 7; recovery, full-app accessibility, actual OS backup
  behavior and Gate A remain outstanding.

## Step 7 — Previewed, reversible CSV imports — 2026-11-10

- **Implemented:** Settings → CSV import/history, Android's real document picker,
  bounded strict UTF-8/quoted-field parsing and explicit account/column/date mapping.
  Support the generic positive-INR amount/direction format and split debit/credit
  columns. Preview counts/reasons/totals, confirm bulk unmatched debit expenses,
  and individually review credits/refunds/transfers and possible matches. Commit
  only the explicitly accepted subset. No transactions are written by preview/cancel.
- **Personal scope:** The user reaffirmed individual use. One person's private
  accounts/ledger remain the scope; no team, organizational-role or business
  administration features are introduced.
- **Replay/matching:** Profile/file identity survives mapping/account changes;
  hashes/row occurrences preserve legitimate identical rows without raw statement
  retention. References or same-leg/date/amount suggest existing events even when
  descriptions differ. Matching is explicit and preserves original fields/source/
  notes/revisions. Opposite transfer statements attach to the same financial event.
  A subset reserves the whole file; later remaining rows need a reduced/corrected
  file and overlap review. An undone file offers explicit batch restoration.
- **Storage:** Reuse schema v1 without a migration. Batch/events/evidence/receipts/
  revision commit atomically. Every receipt's revision/status and later links guard
  all-or-nothing undo/restore; only batch-created events are tombstoned. Generic
  undo cannot bypass import checks. Repeated undo/restore, later edits, edited
  tombstones, linked refunds/evidence, stale previews/races and disk-full rollback
  have explicit assertions. A failing after-insert SQLite trigger proves rollback
  after a write has started rather than merely testing preflight rejection.
- **JVM/build:** **79 JVM tests pass**, zero failures/errors/skips, including
  thirteen CSV parser/mapping/classification tests. Byte/row/column/field caps,
  invalid UTF-8, BOM, quoting/newlines, split columns, strict dates, ambiguous
  categories and the independent SHA-256 `abc` vector are covered. Debug/release/
  test builds pass; final full build **39s**, 128 tasks (25 executed, 103 up-to-date).
  Subsequent test/lint builds pass in **26s** and **21s**; lint reports **No issues
  found**. The final test-only fixture-path repair builds/lints in **21s**, 56 tasks
  (8 executed, 48 up-to-date). Exported Room schema v1 has no diff.
- **Storage phone result:** The final storage invocation passes **35 tests**
  (1.964s), including eight CSV repository cases. Its separate writer/force-stop/
  reader probe passes with a fresh reader process. This run then exposed one
  existing UI fixture read still using the former flat asset path. Both fixture
  consumers now use `ledger/november-baseline.json` under the namespaced test asset
  root; the financial expectations and production code are unchanged.
- **UI regression result:** After that test-only repair, `verify_storage_on_device.py
  --ui-only` passes **12 entry/navigation/privacy/error/layout tests** (72.999s),
  **five budget tests** (37.742s), **six analytics tests** (24.758s), plus the
  expense/budget save/force-stop/fresh-reader stages (8.175s/3.109s). Final launch
  reports **Status: ok**. Together with CSV, **31 UI tests** and four fresh-reader
  verifications pass. The phone is authorized on the isolated ADB socket;
  no ADB server was reset and no phone reconnection is needed for these final runs.
- **CSV phone result:** The CSV driver passes **eight UI/picker tests** (43.367s):
  100-row review/classification/rotation/commit/undo/replay/restore; split columns/
  explicit dates; cancel/no writes; stream cancellation; stale confirmation;
  manual matching/preserved original record; sanitized disk-full failure retaining
  the preview; actual system document selection with exact fixture-hash equality;
  and 200% font/dark preview/refund controls. No required test is skipped. Earlier
  picker synchronization fixes reacquire stale DocumentsUI nodes and wait for the
  requested Downloads root; off-screen mapping controls are reached by scrolling.
- **Independent corpus:** 80 valid debits **₹785.50**, ten valid credits **₹200**,
  ten invalid rows and two distinct identical debits. With two manual matches,
  bulk accepts 78 and leaves twelve for review. Two attachments and explicit
  income/transfer/refund classification yield **83 accepted = 81 new + 2 matched**,
  seven still needing review and ten invalid. Exact net spending **₹765.50**,
  income **₹20**, Bank position **−₹725.50**, Cash **−₹20**. The UI's empty ledger
  imports 83 new events with the same totals. Expected amounts were independently
  updated when the two manual-match fixtures became unique ₹11/₹12 movements;
  conservative equal-movement matching was retained.
- **Real interruption:** A test-only SQLite wrapper blocks after the first actual
  transaction insert in a pending 10,000-row batch. The host then force-stops only
  FinanceGenie Dev. A fresh process verifies precisely the previously committed
  five rows/one batch/five evidence receipts/revision 5, with no partial second
  batch, then undoes the baseline. A second stop/fresh reader verifies durable
  undone replay protection and explicit restore. Final readers pass in
  **0.099s/0.113s**. The wrapper, gate and test database are absent from normal apps.
- **Layout/privacy:** Retrieved only the test-generated 200% dark CSV cache image
  and visually checked wrapping/readable exact amounts. Tests scroll to the review/
  refund controls. Native Material controls use existing themes and accessible
  text; no restyling/dependency change is needed. This is not a new human CSV
  TalkBack run or a claim of tablet/minimum-API coverage. Full-app accessibility
  and broader device checks remain Gate A work. Preview/review choices survive
  rotation in RAM only; cancel/success/process death discards them. No source file,
  financial saved-state draft or permanent URI grant is retained.
- **Performance:** The separate non-debuggable/profileable A063 app imports
  10,000 new rows into 10,000 existing events and verifies all **20,000** events,
  10,000 provenance records and exact spending/position deltas. **5,253.70ms** total
  meets the unchanged **15,000ms** target: parse 47.68ms, preview/classification
  1,646.44ms, atomic commit 3,034.53ms, verification 525.06ms. Benchmark build passes
  in **29s**, 106 tasks. [The reviewed baseline](csv-performance-baseline.json)
  includes app/driver/test APK hashes and sampled PSS **78,251 KiB**. One observation
  is not a P95; prepared input bytes exclude provider/download and human review
  time, and PSS after verification is not a continuously measured peak.
- **Packaging/hygiene:** Normal debug/release APKs contain neither synthetic CSV/
  ledger assets nor test/crash/benchmark setup classes. Their only requested
  permission remains the AndroidX internal receiver permission; there is no
  Internet/broad-storage/SMS/notification collection permission. Unsigned release
  size is **7,260,914 bytes**. `.gitignore` excludes local CSV statements while
  allowing the synthetic fixtures, plus local agent/tool directories. Repository
  hygiene, wrapper checksum and whitespace checks pass. No user ledger/preferences
  were cleared and no broad phone log, personal image or device serial is committed.
  After retaining the reviewed benchmark report, the three synthetic benchmark
  packages were uninstalled and FinanceGenie Dev reopened with its data retained.
- **Reproduction/evidence:** [CSV behavior and commands](csv-import.md),
  `/tmp/financegenie-step7-verified-build.log`,
  `/tmp/financegenie-step7-final-tests-build.log`,
  `/tmp/financegenie-step7-fixture-regression-build.log`,
  `/tmp/financegenie-step7-final-regression-phone.log`,
  `/tmp/financegenie-step7-final-ui-regression-phone.log`,
  `/tmp/financegenie-step7-final-csv-run.log`,
  `/tmp/financegenie-step7-final-csv/`,
  `/tmp/financegenie-step7-final-performance-build.log`,
  `/tmp/financegenie-step7-csv-performance/summary.json`, JVM/lint reports and the
  checked-in synthetic fixtures/tests/drivers. Remote CI is separate evidence.
- **Status:** Step 7 PASS. Next is Step 8 backup/restore/export/local deletion.
  Step 6's startup confirmation/trace limits, budget-save latency, broader device/
  full-app human accessibility, actual OS backup behavior and Gate A remain open.

## Step 8 — Personal backup, recovery and local deletion — 2026-11-10

- **Implemented:** Settings → Backup, export and delete. Full strict versioned
  JSON with exact integer strings, mandatory counts, canonical content checksum
  and explicit plaintext/private-note disclosure before Android's save picker.
  Separate CSV report quotes cells, escapes formula-like text and includes active
  events only, one row per transfer. JSON preserves original text, tombstones,
  financial metadata, budgets, observations and necessary CSV replay/ownership guards.
  Security preferences/credentials, original statements, unfinished drafts, manual
  undo before-images, owner bindings and outbox/session state are excluded.
- **Restore boundary:** Parse/validate fixed-depth UTF-8, field/table caps, IDs,
  references and checked financial totals before staging. Populate an isolated
  empty database, re-read/compare complete records and import receipts, recheck
  current settings/revision under Room's write boundary, publish an atomic private
  filename pointer, retire old writes/cache, close handles and clean old profiles/
  sidecars. A fresh local UUID consistently remaps CSV profile/file hashes while
  preserving financial IDs/account-scoped reference hashes. Activity recreation
  clears prior ViewModels, drafts, navigation and auth grants. Startup resumes
  abandoned-stage/old-file cleanup; cleanup failure remains visible/retryable.
- **Bounds/dependency:** 50 MiB input/output, 100,000 transactions and explicit
  per-table/field caps. Unsupported schema, wrong types, duplicate/unknown fields,
  malformed UTF-8/Unicode, unsafe integer strings, truncation, wrong counts/checksum
  and invalid references are rejected. Pinned Gson 2.11's streaming API is now a
  runtime dependency; fields are manually enumerated, with no reflective serialization.
- **JVM/build:** **86 JVM tests pass**, zero failures/errors/skips, including seven
  new backup/report tests. The 1,000-record pure round trip checks every field,
  signed positions, linked refund/transfers/card payment, observations, budgets,
  notes/newlines/quotes/emoji and tombstones against independent expectations.
  Tests cover invalid schema/UTF-8/surrogates/deep values/field and transaction caps,
  an actual >50 MiB stream, corruption/count mismatch and spreadsheet prefix escaping.
  Final debug/release/test/unit/lint build: **BUILD SUCCESSFUL in 14s**, 128 tasks
  (31 executed, 97 up-to-date). Lint reports **No issues found**. The final separate
  recovery-check app/test build passes in **3s**, 65 tasks (11 executed, 54 up-to-date).
  Exported Room schema v1 has no diff; no migration is invented.
- **Recovery storage:** **Seven tests pass** on the A063 in **7.805s**: 1,000-record
  exact restore/new identity/CSV replay and repeated undo/restore; stale restore/
  delete rejection; failure after a staged insert preserving the selected ledger;
  cleanup of owned DB/WAL files with exported file/privacy preference retention;
  edited imported-row guards surviving restore; and undone created/matched batches
  restoring while preserving the complete original manual record; and a thrown
  cleanup I/O error retaining a visible pending state until successful retry.
  Final review caught that an exception after publication could otherwise omit
  the pending flag; cleanup/close errors now retain it. The first
  fixture attempted to create a tombstone directly; it now creates then deletes
  through normal repository operations, retaining the original expected totals.
- **Recovery UI:** **Four tests pass** in **29.331s**: plaintext disclosure/cancel,
  invalid backup and stale-delete protection; preview surviving rotation then
  cancelling without writes; actual profile switch replacing the old ViewModel;
  real JSON/CSV save pickers and re-opening only the unique created JSON; and dark
  200% font controls/scrollable disclosure. Host inspection verifies **1,000 JSON
  records**, **999 active CSV rows** and formula-prefix escaping. The synthetic dark
  capture was retrieved from test-owned cache and visually inspected: text wraps,
  disclosure and both actions are legible/reachable. This is not a new human
  recovery-specific TalkBack or tablet/minimum-API run; those remain Gate A checks.
- **Phone readiness finding:** The first UI attempt had no visible Compose window
  because the phone was dozing/locked. Read only wake/keyguard status, asked the
  user to unlock, and reran after their confirmation. All required UI cases then
  pass, with no skipped checks, global lock-setting change or credential access.
- **Genuine fresh install:** In the separate **FinanceGenie Recovery Check** app,
  seed/export 1,000 records (**1.889s**), uninstall/reinstall only that disposable
  package, assert an empty ledger/default security preferences, and restore the
  exact retained bytes (**1.463s**). A force-stop/new reader verifies every record
  against the newly scoped source, exact **₹3,258.49 spending**, **₹60,000 income**,
  **Bank ₹51,741.51 / Cash ₹5,000 / Card ₹0**, budgets/notes/tombstone/observation,
  CSV replay and repeated undo/restore, then local deletion (**1.352s**). The
  user-selected export remains after deletion. Host transfer uses shell-v2 stdin
  EOF and creates only the new check app's test-file directory; earlier raw
  exec-out/absent-directory harness attempts were corrected, without changing app
  behavior or financial assertions.
- **Three actual crash boundaries:** Instrumentation-only SQLite/context wrappers
  signal after a real insert in a pending staged transaction, or after durable
  pointer publication before old-file cleanup (restore and deletion separately).
  The host force-stops only the synthetic check app at each signal. Fresh readers
  verify respectively the complete unchanged old ledger (**1.120s**), complete
  replacement/new identity (**1.120s**), and empty new ledger with old files cleaned
  (**1.031s**). These are process-interruption checks, not physical power-loss tests.
- **Regression:** **35 existing storage tests** (2.108s), **12 entry/navigation/
  privacy/error/layout UI** (73.173s), **five budget UI** (38.323s), **six analytics
  UI** (24.437s), **eight CSV UI/picker** (44.047s), storage and expense/budget
  fresh-reader stages, and CSV's interrupted-commit/undone replay readers
  (0.103s/0.115s) all pass. Aggregate: **42 storage, 35 UI and eight fresh-reader
  verifications**, including the new recovery checks. Normal Dev launch reports
  **Status: ok**. Prior performance targets are not reclassified by this step.
- **Isolation/package:** Normal debug/release APKs contain no fixture assets or
  test/crash/benchmark classes. The only requested permission remains AndroidX's
  internal receiver permission; no Internet/storage/SMS/notification collection.
  Release size **7,404,310 bytes**, SHA-256
  `6b3a6dd0faec3c516af9d3a19e26529d86d0874ef5988dc3e3f43daca2c02d44`.
  `.gitignore` also excludes default JSON backup names. The two synthetic check
  packages and only driver-owned Downloads exports were removed after evidence
  capture. FinanceGenie Dev reopened with its normal ledger/preferences retained.
  No device serial, broad phone log, personal capture or financial record is committed.
- **Evidence/reproduction:** [Recovery guide](backup-recovery.md), checked-in tests/
  driver, JVM/lint reports, `/tmp/financegenie-step8-cleanup-verified-build.log`,
  `/tmp/financegenie-step8-cleanup-verified-isolated-build.log`,
  `/tmp/financegenie-step8-final-regression-phone.log`,
  `/tmp/financegenie-step8-final-csv-regression.log`,
  `/tmp/financegenie-step8-cleanup-verified-storage.log`,
  `/tmp/financegenie-step8-ui-unlocked.log`,
  `/tmp/financegenie-step8-cleanup-verified-drill.log` and their output directories.
  These are local/device results; remote CI is separate evidence.
- **Status:** Step 8 PASS. Next is offline acceptance/Gate A (Step 9). Startup
  confirmation/trace hardening, budget-save latency, full-app human accessibility,
  broader supported-device coverage and actual OS backup behavior remain open.
  The successful recovery drill does not itself mark Gate A complete.

## 2026-11-10 — Step 9 acceptance work in progress

- **Scope:** Personal-use offline acceptance, preserving FinanceGenie Dev data.
  Extra report caching was removed after the user accepted the measured budget
  latency. Production calculation code and Room schema remain identical to Step 8.
- **Local host checks:** 86 JVM tests pass, debug lint reports no issues, and
  debug/release/benchmark/recovery/acceptance/control APKs compile. Repository
  hygiene and whitespace checks pass. Build evidence includes
  `/tmp/financegenie-step9-full-build.log` and the variant-specific build logs.
- **Current performance:** Twenty verified process-cold startup samples give
  **775.52 ms P95** to UI-confirmed Overview, with **699.46 ms** to committed frame.
  Thirty measurements per operation give summary **185.79 ms**, bounded search
  **2.74 ms**, expense Save **296.89 ms**, and budget Save **314.52 ms** P95.
  All original targets pass except budget Save, explicitly accepted by the user
  at approximately 315 ms for individual use. Its original target flag remains
  false. See [the aggregate](performance-gate-a.json) and
  [method/limitations](performance.md). Disabling generic UIAutomator idle waiting
  corrects test overhead; it does not prove an application startup speedup or
  complete AndroidX TTID/TTFD. Sampled memory is retained with its longer-workload
  comparison limitation.
- **Actual framework extraction on API 35:** Cloud and device-transfer candidate
  outputs are both **zero bytes**. The permissive control outputs **39,484 bytes**
  for each destination and contains all six CE/DE file, preference and database
  canaries. The phone's disabled backup setting is unchanged and the control is
  removed. Raw backup bytes are not retained. This is extraction/filtering
  evidence, not an actual OEM migration or cloud restore. Evidence:
  `/tmp/financegenie-step9-backup-phone/summary.json` and its two test logs.
- **Packaging/source review:** Release and candidate contain no fixture assets,
  benchmark/test classes, setup routes or canary markers. Both request only the
  AndroidX internal receiver permission, without Internet/SMS/storage/notification
  permissions. Production source has no logging calls or scheduled polling APIs.
- **CI repair:** The prior main run failed before build because `sdkmanager` was
  absent from PATH. The initial setup-action run then exposed its removed default
  `tools` package. CI now requests `platform-tools` explicitly; build and emulator
  results are still pending. SDK setup details follow the
  [action's v3 inputs](https://github.com/android-actions/setup-android/blob/v3/action.yml).
- **Phone test harness:** Added waits for actual Compose roots/input state and
  reacquisition after a stale semantics node. Repeated lost-foreground attempts
  were not passes. The user reported using the phone during earlier attempts,
  then made it available untouched. The early exit recurred. A temporary
  candidate-only finish trace identified Android's Back/`RequestFinishCallback`
  path, without a crash or profile recreation. The trace was removed and the
  clean candidate rebuilt; production source is unchanged. The user identified
  their app blocker as the cause and requested all package IDs for whitelisting.
  Those IDs are now in the acceptance guide. Await confirmation before more phone
  UI automation. Completed benchmark packages were removed; the normal app and
  phone settings are preserved.
- **First emulator results:** All build tasks passed. API 26 storage passed, but
  three UI checks failed because its ICU renders a non-breaking space after ₹.
  New matchers allow only that currency spacing while preserving exact signs,
  digits, grouping and decimals. API 34 storage passed, but the entire UI command
  exceeded 180 seconds; the host now allows 600 seconds without changing assertion
  waits or app performance targets. Corrected full matrix results remain pending.
- **Next release matrix attempt:** Both API 26/34 candidates successfully entered
  all six events and checked exact totals/budget remaining, then failed at a test
  that used `performScrollTo` on an uncomposed lazy-list child. The test now scrolls
  the transaction list to the tagged action before finding/clicking it. This is
  a harness correction, without changing app behavior or expected money values.
- **Further release progress:** Both emulators then passed purchase edits,
  linked-delete/undo and the process-local time-zone check. The next failure was
  a test switching from Categories to Overview without returning to Settings;
  revisiting the Settings tab correctly restored Categories. The test now uses
  Back before switching tabs. Full recovery/restart/framework and regression
  results remain pending on the next matrix attempt.
- **Per-install blocker handling:** A fresh phone test after whitelisting still
  exited early after reinstalling the candidate. Reinstallation may have discarded
  the blocker's per-install rule. The phone driver now supports `--reuse-candidate`:
  verify both installed APK hashes, retain their installation, and clear only
  synthetic acceptance data. CI remains a fresh-install check. Three host-only
  mocked-ADB guard checks confirm stale APK rejection before reset and timeout
  errors without device IDs; these are not device-acceptance passes. The current
  candidate's blocker rule/readiness is being rechecked before more phone tests.
- **Release journeys now pass on API 26/34:** Complete golden entry, budget,
  edit/delete/undo, malformed-input preservation, local deletion, new-profile
  restore and fresh-process reads passed. API 34 also passed both installed-rules
  extraction destinations and the positive control. Full CI is still red: two
  existing API 34 UI checks need smaller-screen scrolling/input diagnostics, and
  API 26's forced-agent check incorrectly bypassed the legacy eligibility gate.
  The API 26 parser omits `fullBackupContent` metadata when backup is disabled;
  the actual service rejects such apps before extraction. The corrected test
  queries real system eligibility and separately forces declared XML on a copy
  of metadata, explicitly labelled in its result. See the primary-source links
  in [the acceptance guide](offline-acceptance.md). No production backup policy
  or financial calculation has been changed. Corrected legacy/runtime results
  and the remaining regressions still need successful runs.
- **Legacy correction verified:** API 26 passes actual service eligibility
  (candidate false, positive control true), declared-XML extraction and its
  canary control. The original forced-agent diagnostic produced 373,396 bytes
  after bypassing eligibility and omitting the XML resource; it was not a real
  system backup of an eligible app. Installed backup policy is unchanged.
- **Further regression results:** API 34 passes 35 storage, 12 entry/privacy/layout,
  five budget and six analytics UI tests plus two fresh-process checks. CSV
  exposed off-screen message/cancel targets and preview work that had not finished
  before scrolling to counts. Tests now wait for actual controller completion and
  scroll the containing list, retaining all counts/totals/error/cancellation checks.
  API 26 management tests now wait for committed data to reach an idle UI before
  editing, rather than clicking while the preceding write is still busy. The
  known Compose pre-28 dialog-image exception marks only supplemental captures
  unavailable; large-font save/cancel/value assertions still execute. These last
  corrections compile locally; their complete matrix rerun is pending.
- **Complete-suite CI diagnostic:** [Run 42799414119](https://github.com/nikhil-more/FinanceGenie/actions/runs/42799414119)
  passes the host build and CSV import/picker/interrupted-commit checks on both
  API 26/34. API 26 also passes release/restart/framework, 35 storage, 12 core and
  five budget UI tests; its analytics text replacement and recovery save picker
  still fail. API 34 passes storage and the complete recovery driver, but a chooser
  hits Espresso's unfocused-root keyboard action and core search replacement
  reverts to the previous input. Neither emulator job passes overall.
- **Search defect and focused correction:** Search text was read from the
  background-calculated report state. Both APIs now supplied concrete diagnostics
  showing the old editable text after replacement. Keep immediate Compose input
  in the shared ViewModel while reports continue asynchronously. A new regression
  freezes the report snapshot and checks replacement, clearing, literal text and
  the existing 200-character bound. Financial arithmetic and schema are unchanged.
- **Automation follow-up:** Chooser/form actions use existing scrolling and the
  IME-aware form layout, avoiding Espresso's unfocused-root keyboard action.
  Recovery picking verifies the exact synthetic filename, enabled Save action and
  picker closure, and reports sanitized controller state if export never finishes.
  The next matrix run must verify these changes. Current host build, 86 JVM tests
  and lint pass (`/tmp/financegenie-step9-input-picker-build.log`). The retained
  phone app/test APK updates both succeed and their hashes match. After synthetic
  data reset, the smoke test still times out before its first Compose screen;
  it is not a pass (`/tmp/financegenie-step9-preserved-acceptance.log`). Phone
  automation is paused for a temporary blocker pause. The next retry will retain
  this still-empty installation and data, avoiding another reset/update.
- **Status:** Gate A remains IN PROGRESS until the release journey/fresh reader,
  supported-version checks and full-app human accessibility/privacy review pass.
