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

## 2026-11-09 — Step 5 calendar budgets

- **Financial behavior:** Independent overall/category caps, one per month/scope,
  with exact integer-paise remaining and separate gross/refund/net values from the
  domain report. Transfers, income, adjustments and deleted events are excluded.
  Later refunds can produce negative net spending and more remaining than the cap.
- **Navigation:** Share one selected month across Overview, Transactions and Budgets,
  including future planning months. Future reports show actual recorded activity,
  while account estimates remain clamped to today; no forecast is implied. Supported
  form/navigation years are 0001–9999. Existing transaction date validation stays intact.
- **Limit identity:** Edit only the amount of an existing month/scope. Enforce this
  at both UI and repository boundaries so an edit cannot move a historical cap.
  A zero cap is real; removal returns the scope to no-budget state. Removal is a
  confirmed, revision-checked deletion of configuration, preserving financial rows.
  Revisit budget tombstones when actual sync is introduced, rather than adding
  cloud/audit infrastructure to the offline step.
- **Copy:** Explicit preview/confirmation, new IDs, one Room transaction, existing
  destination values preserved and archived-category sources skipped. A complete
  ledger revision guards the preview, trading occasional rejection of unrelated
  intervening edits for a simple consistent contract. Reopen to review the current
  plan. Copying nothing does not bump the ledger revision. Schema v1 is unchanged.
- **Warnings:** Text-based in-app states at 80%/100%/over limit; exact arithmetic,
  no OS alert permission or scheduling. A zero cap with zero net spending is at
  limit. Refunds can clear a warning. Threshold customization and rollover remain
  outside this step.
- **UI:** Scrollable cards include textual expenses/refunds/remaining and explicit
  warning/over-by labels. These provide accessible amounts without introducing
  charts before Step 6. Stable drafts/revisions, duplicate-save prevention, locked
  window inheritance and existing profile isolation are reused.
- **Verification:** Domain, atomic-storage, UI, large-font and process-restart checks
  pass and are recorded in the Step 5 verification entry. No claimed migration
  exists because this step changes queries/operations rather than table definitions.

## 2026-11-09 — Step 6 offline analytics and performance

- **Report boundary:** Validate a complete, coherent ledger revision before applying
  account/category/search/date activity filters. A selected refund can reference
  an original purchase outside that selection. Overview totals, signed trends and
  recent activity share the same selection as Transactions; deleted rows never
  enter analytics. Filtering a partial snapshot before validation was rejected
  because it would break valid linked-refund history.
- **Independent scopes:** Monthly budgets always use all accounts and the full
  selected calendar month. Estimated tracked positions include the complete
  ledger through today, with an explicit as-of date and scope explanation.
  Activity filters cannot hide overspending against a monthly cap or change the
  meaning of a tracked balance.
- **Presentation:** Use small native signed bars and exact text instead of a chart
  dependency or a pie chart that cannot represent net refunds. Category and monthly
  rows expose signed amounts directly; daily bars have a date/amount text view.
  Daily series cover the selected month or the last thirty days in all-date mode;
  monthly series contain at most six buckets. Chart windows stay bounded without
  narrowing all-date summary totals or recent activity. Floating point sizes
  pixels only; financial labels retain integer-paise arithmetic.
- **Calculation cost:** Batch report requests so full-ledger validation runs once
  and account positions are reused for requests with the same as-of date. Keep
  the pure financial engine as the source of totals rather than duplicating its
  semantics in chart components or SQL aggregates.
- **Measured read optimization:** An initial 10,000-record Save-to-visible
  measurement was 445 ms P95. A revision-checked snapshot cache reduced an
  intermediate measurement to 309 ms P95, still above the proposed 300 ms target.
  Retain one snapshot per repository to avoid duplicate full reads while preserving
  complete before/after validation and atomic writes/history/evidence. Every read
  checks the cache key inside a Room transaction: ledger settings, SQLite
  `total_changes()` and `PRAGMA data_version`. The connection counters guard nested
  rollback/revision reuse and changes through other connections, which the ledger
  revision alone cannot distinguish. This trades retained snapshot memory and a
  few small checks for less repeated loading. Revisit with performance regressions,
  larger recovery/import datasets or changes to database connection ownership.
- **Measurement isolation:** Use a separate `.benchmark` app based on release,
  with debugging disabled, shell profiling enabled and 10,000 synthetic records.
  Its fixed clock, database, preferences and permission-protected setup route are
  excluded from normal debug/release APKs. Measurements never clear or use the
  user's ledger. Warm revision-checked reads and summaries from fresh repository
  instances are reported separately; Save includes the actual click through the
  expected rendered metric and Compose idle. See [the methodology](performance.md)
  for cache/compilation conditions and measurement limits.
- **Capture correction:** Two Macrobenchmark 1.3.4 attempts each yielded only
  thirteen valid startup samples from twenty requested runs. The missing samples
  had truncated process names in the app's traces. Adopt 1.4.1, whose
  [AndroidX matcher](https://android.googlesource.com/platform/frameworks/support/+/3ecf191783372810d8cbe9dededeea09dde6ae07/benchmark/benchmark-traceprocessor/src/commonMain/kotlin/androidx/benchmark/traceprocessor/TraceProcessor.kt)
  recognizes the last fifteen characters when the full process name is unavailable.
  Its artifacts support the pinned SDK 34/AGP toolchain. Keep the requirement for
  twenty valid startup samples; partial captures do not establish a target result.
- **Final measurement choice:** Committed-frame synchronization verifies all
  twenty launches, but standard trace startup extraction still yields 14/20.
  Preserve that result as incomplete. A separate monotonic readiness driver times
  all twenty process-cold launches through exact UI/PID/foreground confirmation;
  its custom metric names do not claim AndroidX TTID/TTFD. The confirmed P95 is
  2,278.64ms, above the unchanged 2,000ms target; the frame-commit diagnostic is
  632.66ms and does not guarantee physical presentation. This trades a conservative
  endpoint with automation overhead for a complete, reproducible baseline while
  keeping trace/confirmation hardening open for Gate A.
- **Verification status:** 66 JVM, 27 phone storage and 23 UI tests plus two
  fresh-process checks pass. Final summary/search/expense-save P95 values are
  187.30/2.99/278.76ms and meet their budgets. Analytics and baseline collection
  complete Step 6; startup limitations, budget-save latency and other Gate A
  requirements remain recorded in the verification log and progress tracker.

## 2026-11-10 — Step 7 personal CSV imports

- **Individual scope:** The user reaffirmed personal use. Keep one person's own
  cash/bank/card accounts and private ledger; shared workspaces, organizational
  roles, approval hierarchies and business administration are outside scope.
  Optional later milestones still need separate scope authorization.
- **Input boundary:** Android's document picker and a bounded strict UTF-8 parser
  support the generic format and explicit debit/credit-column/date mapping.
  Quoted commas/newlines/quotes and BOMs are supported; ambiguous dates/signs and
  unknown categories are never guessed. Keep at most 10 MiB/10,000 data records,
  64 columns and 4,000 characters per field. No broad storage permission, retained
  original file or permanent provider access is needed.
- **Review:** Debit/credit establishes account movement, not economic type.
  Explicitly confirm bulk expense classification only for valid unmatched debits
  with known categories. Credits, transfers, refunds and possible matches need
  row review. Plan against one ledger revision and validate the accepted group
  again inside commit. Invalid/unresolved rows are excluded with visible reasons.
- **Matching:** Account-scoped reference hashes are the strongest signal; otherwise
  same-leg/date/amount suggests existing events even when descriptions differ.
  This deliberately asks for extra review of equal-value movements so manual
  entries and opposite transfer descriptions cannot silently duplicate spending.
  Matching attaches provenance without rewriting the original event. One event
  cannot absorb two rows in one file; legitimate identical new rows remain distinct.
- **Replay:** Store versioned component hashes binding profile, account, mapping
  and original bytes, plus logical row occurrences. Preserve the original
  profile/file identity across remapping/account changes. Committing a subset
  reserves the whole file; remaining rows require a reduced/corrected file and
  overlap review. This conservative choice favors explicit review over silently
  appending rows or importing byte-identical files into another account.
- **Durability:** Reuse schema v1's batch/evidence/change-receipt tables; there is
  no schema migration. Commit events, evidence, receipts, batch and ledger revision
  atomically. Batch undo/restore checks every linked record's revision/status and
  later links, and rejects the whole operation on conflict. Only new batch-owned
  events are tombstoned; pre-existing matched financial records remain intact.
  Generic transaction undo cannot bypass batch rules. Explicit restore retains
  replay protection and supports repeated revision-checked undo/restore cycles.
- **Draft lifetime:** Preview/review choices survive rotation only in ViewModel
  memory. Cancel/success/process death discards them; financial drafts, file bytes
  and provider URIs are not serialized to saved state. The user reopens a file
  after process death; committed batch state remains durable.
- **Measurement:** A separate non-debuggable/profileable app imports 10,000 rows
  into 10,000 existing events in 5,253.70ms through exact verification, below the
  15-second target. One observation excludes provider latency and human review;
  it is not a P95 claim. Sampled PSS is not a continuous peak measurement.
  See [the CSV guide](csv-import.md) and [baseline](csv-performance-baseline.json).
- **Revisit:** Actual bank adapters, import-volume regressions and Step 8 portable
  restore. Restoring to a new profile must re-scope hashed file/evidence identities
  from stored components without retaining raw statements or bank references.

## 2026-11-10 — Step 8 personal recovery

- **Format/trust:** A versioned strict streaming JSON schema with exact integer
  strings, mandatory entity counts and a canonical content checksum. Checksum
  detects accidental corruption, not authenticity. Plaintext/private-note exposure
  is disclosed before a system save picker; no permanent provider grant or private
  export copy is retained. Use pinned Gson 2.11's streaming reader/writer at runtime
  for strict token handling and JVM testability, instead of Android-only JSON or
  reflection-based automatic object serialization. This adds one existing cached
  dependency to runtime; model fields are explicitly enumerated.
- **Bounds:** 50 MiB and 100,000 transactions, with separate table caps and existing
  per-field limits. Reject unsupported/unknown/duplicate schema fields, unsafe
  numeric token types, invalid Unicode, references and checked arithmetic before
  writing a staged database. Never truncate data to fit the backup limit.
- **Replacement:** Build an empty separate Room database, populate/validate it and
  re-read all financial records/provenance/guards before selection. Preserve IDs,
  notes, metadata and tombstones while creating a fresh local identity. Recompute
  CSV profile/file keys consistently; account-scoped reference hashes remain valid.
  Keep schema v1 and use an atomic app-private filename pointer rather than renaming
  an open SQLite/WAL database or overwriting the current ledger in place.
- **Concurrency/lifetime:** Recheck the current complete ledger settings/revision
  under its Room write boundary before publishing. Retire old repository writes,
  close the old database, discard cached snapshots and clear retained ViewModels/
  drafts/navigation on profile replacement. Keep the final selection/cleanup phase
  non-cancellable; startup removes unselected stages/old database sidecars. A
  cleanup failure stays visible and retryable, rather than claiming complete deletion.
- **Portable history:** Manual undo groups and prior financial before-images are
  disposable and excluded. CSV's minimal batch/transaction ownership flag and
  accepted revision are necessary provenance, so include these small receipts.
  Reconstruct only their guarded batch operation, preserving edited-row conflicts
  and explicit undone replay/restore. Attaching evidence still cannot rewrite a
  pre-existing financial event. No owner bindings, device credentials/preferences,
  pending previews/candidates, sessions or outbox work are restored.
- **Export/deletion:** A separate CSV report includes active events, one transfer
  row, exact amounts and notes. Quote every cell and prefix formula-like text with
  an apostrophe, explicitly disclosing that JSON preserves exact original text.
  Confirm local deletion against the current revision, activate a new empty ledger
  and remove old app-owned profiles/sidecars. Keep exported documents at their
  chosen locations and retain device security preferences, avoiding an accidental
  weakening of app-lock/screen protection. Physical secure flash erasure is not promised.
- **Test isolation:** The user supplies the phone. Test namespaces prevent normal
  ledger/preferences from being touched. A separate `.recoverycheck` package enables
  real uninstall/reinstall recovery; only that disposable package is removed.
  Instrumentation-only wrappers interrupt actual staged inserts and cleanup after
  pointer publication. See [the recovery guide](backup-recovery.md).
- **Revisit:** Real schema upgrades, larger recovery-volume measurements or a
  separately scoped encrypted portable-backup requirement. Individual local use
  does not require accounts/sign-in, teams, roles or cloud infrastructure.
