# FinanceGenie progress tracker

Updated 9 November 2026. Product scope and financial rules live in [plan.md](plan.md).
This file owns the current step status, next actions and tradeoffs. Detailed test
commands/results live in [the verification log](docs/verification-log.md); durable
architecture decisions live in [the decision record](docs/decisions.md).

## Working agreement

- Current implementation scope is milestone A, the offline Android app. Later
  milestones are tracked but need their own scope decision.
- The user will connect their physical phone whenever device testing is required.
  Current test hardware: **A063, Android 15 / API 35**. Never commit its serial,
  private phone content or financial records. Use the isolated development app and
  synthetic fixtures; do not wipe device/app data as a routine test step.
- Update this file after each implementation step and whenever its status changes.
  Every completed step records its decision, alternative, reason, consequence and
  revisit condition. Planned tradeoffs are not represented as decisions already made.
- Status is `NOT STARTED`, `IN PROGRESS`, `PASS`, `BLOCKED` or `DEFERRED`.
  Record implementation and verification separately; a mandatory unrun check
  cannot be marked PASS. Pending device/manual checks never disappear from a gate.
- Pure JVM model work may proceed while foundation manual device checks are
  pending, because it does not depend on Android UI behavior. Gate A still requires
  every applicable device check. This exception is explicit, not an assumed pass.

## Step dashboard

| Step | Work | Status | Evidence / next action | Tradeoff record |
|---|---|---|---|---|
| 0 | Requirements, financial contract and threats | PASS | [Requirements](docs/product-requirements.md), [verification](docs/verification-log.md#step-0--foundation-requirements-and-decisions--2026-11-09) | Below |
| 1 | Android project and checks | PASS | Builds/lint, two physical-phone navigation tests, launch and user readability/tab confirmation pass | Below |
| 2 | Pure financial model and golden fixtures | PASS | 35 JVM tests, debug/test APK builds and lint pass; [fixture](fixtures/ledger/november-baseline.json), [verification](docs/verification-log.md#step-2--pure-financial-model-and-golden-fixtures--2026-11-09) | Below |
| 3 | Room persistence and atomic operations | PASS | 17 phone storage tests plus fresh-process probe; 35 JVM tests, debug/release/test builds and lint pass | Below |
| 4 | Accounts, manual transactions and navigation | PASS | 45 JVM tests, 12 phone UI tests, restart probes and user-confirmed lock/preview/TalkBack checks pass | Below |
| 5 | Calendar budgets | PASS | 55 JVM tests, 23 phone storage tests, 17 UI tests and expense/budget restart probe pass; schema unchanged | Below |
| 6 | Offline overview and analytics | PASS | 66 JVM, 27 storage, 23 UI tests and fresh-process checks pass; 20-launch/30-operation baseline recorded, with startup limitation retained for Gate A | Below |
| 7 | CSV preview/import/undo | NOT STARTED | After Step 6 | Record when started |
| 8 | Backup, restore, export and local deletion | NOT STARTED | After Step 7 | Record when started |
| 9 | Offline acceptance / Gate A | NOT STARTED | Requires Steps 0–8 and phone evidence | Record when started |
| 10 | Supported notification parsers | NOT STARTED | B scope decision after A | Record when started |
| 11 | Consent and durable capture | NOT STARTED | After Step 10 | Record when started |
| 12 | Replay and duplicate reconciliation | NOT STARTED | After Step 11 | Record when started |
| 13 | Review inbox and merchant rules | NOT STARTED | After Step 12 | Record when started |
| 14 | Detection reliability / Gate B | NOT STARTED | Requires physical-phone verification | Record when started |
| 15 | Cloud protocol/rules/cost feasibility | NOT STARTED | C scope decision after A | Record when started |
| 16 | Identity and isolated profiles | NOT STARTED | After Step 15 | Record when started |
| 17 | Outbox and conflict-aware sync | NOT STARTED | After Step 16 | Record when started |
| 18 | Deletion/recovery / Gate C | NOT STARTED | After Step 17 | Record when started |
| 19 | Authenticated read-only web | NOT STARTED | D scope decision after C | Record when started |
| 20 | Shared analytics / Gate D | NOT STARTED | After Step 19 | Record when started |
| 21 | Release hardening and acceptance | NOT STARTED | All included milestone gates | Record when started |
| 22 | Distribution review / Gate E | NOT STARTED | After Step 21; publication needs authorization | Record when started |
| Optional | SMS, AI, rollover, custom periods, recurrence, web editing | DEFERRED | Separate requirements and scope decision | Not adopted |

## Decisions and tradeoffs by step

### Step 0 — Contract before implementation

- **Decision:** One INR ledger, Asia/Kolkata effective dates, calendar-month caps,
  refunds in their received month and explicit transfer/card-payment semantics.
- **Alternative:** Currency conversion, custom periods and retroactive refund attribution.
- **Reason:** Establish a small, consistent financial contract across imports,
  Android and future web calculations.
- **Consequence:** Later cross-month refunds can create negative monthly spending;
  show gross spending/refunds clearly. Advanced budgeting remains deferred.
- **Revisit:** Only with a requirements/data-migration decision and matching fixtures.

### Step 1 — A reproducible, private project foundation

- **Decision:** One Compose app module, constructor injection when needed, a pinned
  SDK 34/JDK 17 toolchain, explicit backup exclusions and separate debug identity.
- **Alternative:** Multiple modules, DI framework, cloud SDKs and a newer uncached toolchain.
- **Reason:** Keep the initial app buildable and inspectable using installed tools;
  isolate development data while postponing infrastructure with no current feature.
- **Consequence:** Upgrade toolchain/target SDK before public distribution. App-private
  storage and backup rules do not claim database encryption. UI is still an empty
  foundation until the ledger and management screens exist.
- **Revisit:** Measured module/wiring needs, distribution SDK requirements or a new
  threat-model requirement. Both navigation/recreation tests passed on the phone;
  cold launch reported 862 ms (one observation, not a performance benchmark).
  The user confirmed readable text and usable tabs. Full large-font, theme and
  TalkBack checks remain scheduled for Step 4/Gate A.

### Step 2 — Exact, independently testable financial behavior

- **Decision:** Pure Kotlin economic models and validation/calculation
  functions with injected clock, integer paise and shared synthetic JSON fixtures.
- **Alternative:** Put arithmetic in screens/Room queries or require Android for tests.
- **Reason:** Verify financial rules before storing real data and share expected
  results with future import/web implementations.
- **Consequence:** This step adds no transaction-entry UI or persistence. Snapshot
  validation/calculation favors clarity; optimize only with measured data sizes.
  Storage/audit metadata will be introduced at its actual Room boundary in Step 3.
- **Exact arithmetic:** Accumulate with `BigInteger` and explicitly range-check the
  final `Long`, preserving totals under reordering/cancellation. This costs some
  allocation compared with a `Long` fold, but avoids order-dependent intermediate
  overflow. Explicit bounds support API 26; lint rejected the newer-platform
  `BigInteger.longValueExact()` method, so it was replaced rather than suppressed.
- **Input versus transport:** User/record inputs retain the plan's paise ceiling.
  Canonical integer-string transport supports the full signed 64-bit range for
  computed totals. Decimal input accepts plain ASCII digits/dot; format-specific
  grouping/currency normalization belongs to later import/parser adapters.
- **Fixture dependency:** Gson is test-only to load the shared JSON without an
  Android runtime. It is not added to the app's runtime dependencies. The fixture
  schema is separate from the future Room/portable-backup schemas.
- **Revisit:** Performance results at 10,000 records or later storage/query boundaries.
- **Verification:** 35 JVM tests passed (7 money, 15 calculations, 11 validation,
  2 shared-fixture tests), with no failures/errors. Debug and instrumentation APK
  compilation passed; Android lint found no issues. The fixture checks all metrics
  against stored independent expectations and 25 shuffled ledger orderings.

### Step 3 — Durable local ledger with explicit write boundaries

- **Decision:** Room schema v1 with separate storage/domain records,
  integer money, foreign keys/indexes, revision-checked repository operations and
  atomic transaction groups with undo history. A ledger revision drives consistent
  Flow snapshots instead of combining independently emitted table states.
- **Alternative:** Serialize the ledger to a file, put Room annotations in the
  economic model, silently replace rows, or independently combine table flows.
- **Reason:** Preserve validated financial behavior and make stale edits, interrupted
  groups and observation of partial state testable before building entry screens.
- **Consequence:** Snapshot validation favors clarity over incremental validation.
  Import-batch schema is reserved for Step 7; no CSV/notification/cloud ingestion
  UI is introduced here. Undo is local and revision-checked, not an immutable bank audit.
- **Schema:** Ten typed tables, indexed date/account/category queries, foreign keys
  and a non-null budget scope key that also enforces overall-budget uniqueness.
  Room/KSP versions match the installed Kotlin baseline (not a latest-version claim).
- **Formatting/history:** Room owns exported-schema formatting; the JSON is still
  validated. Two narrowly scoped processor warnings are suppressed because a
  historical row payload intentionally does not inherit the live entity's keys
  or indexes. Live tables retain their independently tested constraints.
- **Observation:** Every repository mutation increments the ledger revision in the
  same transaction. Flow loads a complete transactional snapshot, trading extra
  reads for consistency and a simple UI boundary. Share one DB instance per profile.
- **Revisit:** Measured 10,000-record performance, actual schema upgrades and later
  import/sync requirements. Never add a dummy migration or destructive fallback.
- **Verification:** 17 storage tests passed on the physical A063; two additional
  probe invocations verified persistence after a development-app force-stop and
  enforced a different reader process. All 35 JVM tests passed. Debug, unsigned
  release and test APKs build; lint has no issues. Packaged permissions/fixture
  isolation and exported integer-money schema checks pass.
- **Evidence:** [Local storage contract](docs/local-data.md),
  [Step 3 verification](docs/verification-log.md#step-3--room-persistence-and-atomic-operations--2026-11-09),
  and `scripts/verify_storage_on_device.py`. Actual upgrade testing starts with
  the first real schema change; performance/recovery gates are still outstanding.

### Step 4 — Usable local management and privacy

- **Decision:** One application-scoped repository, lifecycle-aware UI
  state and stable/revision-bound form drafts. Reuse the domain engine for totals
  and repository transactions for financial edits; retain source labels and private notes.
- **Alternative:** Calculate in screens, silently overwrite stale forms, create
  independent transfer legs, or store a custom app PIN.
- **Reason:** Make the verified ledger usable without weakening its boundaries.
- **Privacy choice:** Use Android's device-credential confirmation, with cold-start
  locking and a configurable background timeout (default 30 seconds). Screen
  protection is enabled by default and can be disabled explicitly. No PIN is read
  or stored by the app; biometric-specific integration is not required for this path.
- **Consequence:** Room schema stays v1. Complete snapshots keep totals/filters
  consistent; optimize after measurement. Unsaved form drafts survive rotation,
  while only committed records survive process death. Device-auth grants are never
  persisted. Tiny privacy preferences use synchronous commit before reporting success.
- **Deletion:** Linked purchase/refund deletion is confirmed and atomic; undo is
  revision-checked. A deleted-record filter also exposes later restoration.
- **Device findings:** Fixed a live window-policy update bug found by phone tests.
  Transient snackbars can cover a row action until dismissed; tests dismiss saved
  confirmations before the next row action. No sleeps or relaxed financial checks
  are used to turn a failed result into a pass.
- **Revisit:** Measured filtering costs, biometric convenience, accessibility
  feedback, and real schema additions. Budgets/charts remain Steps 5–6.
- **Verification:** 45 JVM tests and debug/release/test builds pass; lint has no
  issues. All 12 UI tests passed on the A063, plus separate save/restart/read
  invocations enforcing a new reader process. The 17 storage tests and their
  two-stage probe also passed during this step. Compose-local 200% font/dark/light
  interactions passed and synthetic captures were visually inspected. The user
  confirmed successful real device-credential enable/unlock/cancel and the 30-second
  background timeout, protected screenshots/recent-app previews, and usable
  TalkBack labels/controls on the A063. Step 4 acceptance is complete.

### Step 5 — Explicit calendar-month limits

- **Decision:** Reuse exact ledger reports for separate overall/category
  limits, including expenses/refunds/net/remaining. Allow planning future months
  through the shared month selector. A zero limit is a real budget; removing a
  limit returns its scope to no-budget state.
- **Copy policy:** Confirm an atomic next-month copy, create new IDs, preserve
  existing target limits and skip archived categories. Preview counts and reject
  a changed ledger revision rather than copying different values silently.
- **Alternative:** Automatically roll limits forward, replace destination values,
  or treat category limits as allocations of the overall cap.
- **Reason:** Keep historical months independent and make every write explicit.
- **Consequence:** Budget removal deletes configuration only, with a revision check;
  financial events/history stay intact. Room schema remains v1. In-app text warnings
  start at 80%, distinguish reaching/exceeding a limit, and require no notification
  permission. There is no rollover or background alert scheduling.
- **Revisit:** Actual sync tombstones/schema needs in C, user feedback on warnings,
  and measured query/render performance in Step 6.
- **Verification:** 55 JVM tests, 23 phone storage tests, five budget UI journeys
  and 12 navigation/transaction/privacy/layout regression tests pass. Separate
  save/force-stop/read invocations verify a ₹500 limit and ₹300.50 remaining against
  a persisted ₹199.50 expense in a new reader process. The 200% dark budget capture
  was visually inspected; debug/release/test builds and lint pass with no schema
  change or new permission. [Evidence](docs/verification-log.md#step-5--calendar-budgets--2026-11-09).
- **Test synchronization:** Existing filter assertions now wait for the expected
  asynchronous result. Lock tests verify visible navigation and explicitly choose
  Overview after unlocking instead of assuming a restored destination. Expected
  financial/session results are unchanged, and no test is skipped.

### Step 6 — Coherent offline analytics and measured performance

- **Decision:** Calculate scoped Overview metrics/trends and recent
  activity from one complete validated ledger revision. Share account/category/
  search/date filters with Transactions. Deleted records stay out of analytics;
  monthly budgets and current tracked positions retain their full-ledger meaning.
- **Visuals:** Small native signed bars with exact textual amounts, a zero baseline,
  and accessible daily/monthly alternatives. Refund-heavy categories can be negative.
  Charts are bounded to a selected month/30 days and six monthly buckets.
- **Alternative:** Add a chart library, filter a partial snapshot before validation,
  or compare budget caps against a filtered subset of spending.
- **Reason:** Preserve linked-refund validity, exact arithmetic and clear scope.
- **Performance method:** Use an isolated release-like/profileable benchmark app and
  10,000 synthetic events. Measure startup, summary/search and save-to-visible paths
  before making performance claims; keep raw traces/device identifiers uncommitted.
- **Measured optimization:** Avoid duplicate full reads with one cached snapshot,
  checked against ledger settings and SQLite local/cross-connection change counters.
  The additional counters handle rollback/reused revisions, including a separate
  DB connection. This retains one snapshot in memory while preserving full
  validation, atomic history/evidence and stale-edit checks. Save
  P95 improved from 445ms initially to 278.76ms in the final measurement, within
  the proposed 300ms target. An intermediate 309ms miss is retained in the history.
- **Capture correction:** Macrobenchmark 1.3.4 missed truncated process names,
  yielding 13/20 samples twice. Compatible 1.4.1 improves matching without a
  toolchain upgrade, but standard TTID/TTFD still yields only 14/20 after a
  committed-frame barrier. It remains explicitly incomplete. The direct readiness
  driver verifies process absence, nonce/PID, a committed loaded frame and exact
  visible values across all twenty launches; it does not fill missing trace metrics.
- **Verification:** 66 JVM tests, 27 phone storage tests, 23 UI tests and two
  fresh-process checks pass. Golden/filter/edit convergence, empty/single/1,000-row
  data, negative categories and 200% long-label layouts pass. Builds/lint pass,
  schema v1 and normal permissions are unchanged. The reviewed 10,000-record
  baseline includes twenty cold launches and thirty samples per operation: summary
  P95 187.30ms, search 2.99ms and expense Save-to-visible 278.76ms pass their targets.
  The conservative UI-confirmed startup P95 is 2,278.64ms, above 2,000ms; committed
  frame P95 is 632.66ms but is not substituted for confirmation/presentation timing.
- **Consequence/revisit:** Step 6's accurate analytics and baseline gate is complete.
  Keep startup confirmation/trace hardening and budget-save latency open for Gate A;
  the startup budget is not relaxed. The baseline includes automation overhead,
  uses warm OS caches/installed compilation state, and samples rather than
  continuously measures PSS. Revisit cache ownership when restoring/replacing a DB
  and compare future imports/recovery at their actual dataset limits.
- **Evidence:** [Analytics behavior](docs/analytics.md),
  [measurement method/results](docs/performance.md),
  [aggregate baseline](docs/performance-baseline.json) and
  [verification](docs/verification-log.md#step-6--offline-analytics-and-performance-baseline--2026-11-09).

## Current actions and outstanding checks

1. Implement CSV preview/import/undo (Step 7).
2. Before Gate A, address startup confirmation/trace limits and measure budget-save
   latency. Do not claim a twenty-run AndroidX TTFD result from the incomplete capture.
3. Device-backup behavior remains a later Gate A check; never commit a full phone
   log or personal screenshot.
