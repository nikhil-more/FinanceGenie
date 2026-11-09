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
  Gradle 8.8 and permits newer releases with possible compatibility warnings.
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

## 2026-11-09 — Start offline implementation and maintain progress

The user has now requested implementation under the plan, superseding the earlier
setup-only boundary. `progress_tracker.md` owns the current step dashboard,
tradeoffs and next actions; this file records durable decisions, while the
verification log retains exact outcomes. Later optional milestones keep their
separate scope gates.

The user will provide a physical phone for testing. An A063 running Android 15 /
API 35 is connected: navigation/recreation instrumentation and app launch passed,
and the user confirmed readable text and all four tabs working. Do not store its
serial, full device logs or personal screen contents. This is a reference-device
choice; one launch timing does not satisfy the performance benchmark.

## 2026-11-09 — Step 2 financial domain

- **Boundary:** Pure Kotlin account/category/committed-event/budget/observation
  models, ledger/change validation and financial reports. No Android imports,
  persistence, UI arithmetic or candidate auto-posting. Storage/audit metadata
  belongs to Step 3, avoiding unused infrastructure in the economic model.
- **Arithmetic:** Integer paise and exact decimal parsing. Aggregate with
  `BigInteger` and check the final signed `Long` range, so intermediate cancellation
  cannot make results depend on row order. Explicit range conversion supports
  Android API 26; lint caught `BigInteger.longValueExact()` requiring API 31.
  Reject out-of-range results rather than truncate or raise the minimum API.
- **Transport:** Canonical integer strings round-trip signed 64-bit totals.
  User inputs and record fields retain the `1,000,000,000,000` paise ceiling.
  Reject grouping symbols, exponents and excess decimals at the plain-input boundary;
  later source adapters must normalize their explicitly supported formats.
- **Invariants:** One atomic transfer; signed liabilities; refund-category snapshot
  and explicit unlinked-refund confirmation; cumulative linked-refund limits;
  half-open ledger-local ranges; archived references allowed for history/corrections
  but not new selection. Snapshot/change validation prioritizes clarity over
  incremental indexes; reconsider after measured 10,000-record performance.
- **Fixtures:** Shared synthetic JSON with independently entered expected metrics.
  Gson 2.11.0 is a JVM test dependency only. The fixture schema is not a Room or
  portable-backup format; no runtime parser/network/AI library is introduced.
- **Tests:** 35 JVM cases pass, including exact golden totals, permutations,
  transfer/card/refund cases, invalid links, deletion/undo, archive selection,
  overflow, leap day, clock zone and month boundaries. Build/lint also pass.

## 2026-11-09 — Step 3 local persistence

- **Storage boundary:** One app-private Room database per profile, independent
  storage records and a constructor-injected repository. Keep one DB instance at
  application scope when wiring Step 4. The domain remains Android-free.
- **Tooling:** Room/runtime/schema plugin 2.6.1 and KSP 2.0.20-1.0.25 match the
  existing Kotlin 2.0.20 baseline; Coroutines 1.8.1 supplies suspend/Flow support.
  These are verified pinned versions, not latest-version claims. Revisit as a
  tested set during the required pre-distribution toolchain upgrade.
- **Writes:** Validate complete before/after financial snapshots, then atomically
  persist event groups, typed prior rows, evidence and ledger revision. Inserts
  abort on duplicate IDs; updates require the expected revision. Avoid REPLACE,
  hard deletion of financial records and silent stale overwrites.
- **Observation:** A transactional ledger-revision increment drives Flow reads of
  a complete transactional snapshot. This adds full-snapshot reads but avoids
  combining inconsistent table emissions. Optimize only after actual measurements.
- **Constraints:** Account/category/refund references have foreign keys and
  targeted indexes. Deferred refund self-references support grouped writes in any
  order. A non-null month/scope key enforces even overall-budget uniqueness,
  avoiding SQLite's multiple-NULL behavior.
- **Undo/provenance:** Undo is local, atomic and revision-checked. Creation undo
  leaves a tombstone and source replay key. Newly attached evidence on prior
  records is detached rather than forgetting its key. This is not a tamper-proof
  bank audit. Import-batch bookkeeping has no ingestion UI/API until Step 7.
- **Schema history:** Export and preserve v1 using the Room Gradle plugin. No
  destructive fallback and no invented v2. The first real change needs a version
  bump plus populated-fixture migration tests. CI checks for changed/untracked
  schema outputs after builds.
- **Generator ownership:** Exported Room JSON has no final newline. Only generated
  schema JSON is exempt from that formatting rule; syntax/credential checks still
  apply. Suppress only the two expected Room warnings about live entity keys/indexes
  not applying to an embedded historical payload; live constraints remain tested.
- **Evidence:** 35 JVM tests and 17 phone storage tests pass, plus an explicit
  two-invocation fresh-process probe. Debug/release/test APK builds and lint pass.
  The phone test suite uses only in-memory/test-named databases, never clears the
  normal app ledger/data, and does not log its device serial.

References checked for this boundary:

- [Room schema export and Gradle plugin](https://developer.android.com/jetpack/androidx/releases/room#use-the-room-gradle-plugin)
- [Preserving schema migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Asynchronous/observable Room queries](https://developer.android.com/training/data-storage/room/async-queries)

## 2026-11-09 — Step 4 manual entry and privacy

- **Wiring:** One application-scoped Room repository with constructor-injected,
  lifecycle-aware ViewModels. No DI framework, new schema version or runtime
  financial fixture is needed. A debug-guarded test container isolates phone UI
  tests from the actual ledger and privacy preferences.
- **Reports:** Use the domain calculator on complete revision-consistent snapshots.
  Monthly metrics follow the selected month; account/tracked positions are always
  current and explicitly estimated. Format integer paise through `BigDecimal`,
  never floating-point money. Filters run over that same coherent snapshot on a
  background dispatcher. Measure full-snapshot/filter costs at Step 6 before adding
  incremental state or paging to the UI.
- **Forms:** Stable draft UUIDs, a synchronous in-flight save guard, original record
  revisions, bounded values and sanitized error resources. Do not silently rebase
  an open form. Retain source labels, refund category snapshots and archived flags.
  Date-only entry does not invent timestamps; changing a timed record's date drops
  its event-time precision with an explanation. Drafts survive rotation in memory;
  committed data survives process death. Unsubmitted drafts are not written to
  persistent or saved-instance storage.
- **Deletion:** Soft delete and receipt-based undo reuse the repository boundary.
  Confirm and atomically delete a purchase and active linked refunds when needed;
  undo restores the whole group. Deleted-record filters offer later restoration.
  A stale receipt fails rather than overwriting a subsequent correction.
- **Authentication:** Use Android's device-credential confirmation, available on
  the minimum API, with narrowly scoped deprecation suppression. Avoid custom PIN
  storage and an extra biometric library. Cold starts lock; elapsed-realtime
  background timeouts offer 0/30/60/300 seconds, default 30. Authentication grants
  stay in memory. Cancel/failure does not unlock an enabled lock. Biometric-specific
  convenience can be considered later without changing financial storage.
- **Screen policy:** Default screenshot protection uses `FLAG_SECURE`; dialogs
  inherit it. Financial composition/semantics are removed while gated, including
  the background grace period. An outer saveable-state holder retains navigation
  when the gate temporarily hides it. API 33+ additionally suppresses recent-app
  screenshots whenever app lock is enabled. Explicit foreground-sharing preference
  changes update the window flag through an observed composition value. The phone
  test caught and fixed an initial deferred-state-read bug in this update.
- **Preference durability:** Commit the three tiny privacy settings before
  reporting success, with a sanitized save-failure message. This adds a small,
  infrequent synchronous disk write, chosen over asynchronously losing a just-enabled
  lock during process death. It does not store credentials or encrypt the database.
- **Verification boundary:** Session/flag/semantics tests can inject a grant only
  in test code. They do not prove that a real Android credential was accepted.
  Record system-prompt, cancellation, OEM preview and TalkBack results separately
  after the user tests them on the connected phone. Do not change global accessibility
  or device-security settings automatically.

References checked for this implementation:

- [Android device-credential confirmation](https://developer.android.com/reference/android/app/KeyguardManager#createConfirmDeviceCredentialIntent(java.lang.CharSequence,java.lang.CharSequence))
- [Secure window flag](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)
- [AOSP Android 15 recents policy implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/Activity.java#L9037)
