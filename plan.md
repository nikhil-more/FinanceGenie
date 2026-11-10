# FinanceGenie — Product and Implementation Plan

**Revision:** 2 — 9 November 2026

**Status:** Offline Android implementation is authorized and underway. Current step status and tradeoffs are maintained in `progress_tracker.md`; actual verification evidence is in `docs/verification-log.md`. The user will provide a connected phone for device testing; an A063 running Android 15 / API 35 is currently available. Proposed defaults below can be changed before their dependent step.

**Audience:** One person using a private Android app first; optional multi-device sync, web access, and Google Play distribution later.

**Goal:** Record and understand personal spending accurately, with useful budgets, dependable recovery, and optional transaction detection. The app must remain useful offline.

**Document role:** This is the canonical product and engineering specification. `FinanceGenie_full_copyable_prompt.txt` is the companion execution prompt; it references this plan rather than duplicating it. Instructions for future implementation in either document do not authorize starting implementation during a planning review.

## 1. Scope and decisions

### Milestones

| Milestone | Required scope | Exit gate |
|---|---|---|
| A — v0.1 offline Android | Accounts, manual transactions, correct transfers/refunds/card payments, calendar-month budgets, basic analytics, generic CSV import, JSON backup/restore, local deletion and privacy controls | Steps 0–9; Gate A |
| B — v0.2 detection | Opt-in notification access, tested parser adapters, conservative duplicate handling, review inbox, merchant rules | Steps 10–14; Gate B |
| C — v0.3 sync foundation | Opt-in identity, owner isolation, tested conflict-aware synchronization, cloud/account deletion | Steps 15–18; Gate C |
| D — v0.3 web | Private, authenticated, read-only dashboard with Android-equivalent calculations | Steps 19–20; Gate D |
| E — v1.0 public release | Hardening and current distribution-policy checks for the features actually shipped | Steps 21–22; Gate E |

A is independently useful. B, C, and D are separate opt-in scope decisions; C depends on A but does not require B, and D depends on C. E can release an offline product without B–D. Deferred features must be absent from shipped claims and permissions. No AI or SMS work is a release prerequisite.

### Proposed defaults and decision deadlines

These defaults preserve the original personal-use, INR-first intent. They are planning decisions, not claims that hardware, accounts, or providers have already been verified.

| Decision | Default | Resolve or recheck before |
|---|---|---|
| Android baseline | `minSdk 26`; choose compatible stable compile/target SDK, Kotlin, AGP, Gradle, JDK, Compose and Room versions as one tested set | Step 1; recheck target SDK requirements before Play submission |
| Currency and reporting zone | One ledger currency, INR; integer paise; reporting zone `Asia/Kolkata`, independent of phone timezone | Step 0 |
| Budget period | Calendar month, no rollover; overall and category caps are independent | Step 0 |
| Refund treatment | Reduce spending in the refund's own month; do not rewrite the purchase month | Step 0 |
| Distribution | Private Android build first; no publishing or billing implied by this plan | Each external release |
| Notification coverage | Start with at most two explicitly named banking/payment app formats; review-first capture by default | Step 10; select packages and synthetic fixtures then |
| Cloud | Disabled by default; emulator first; choose region and verify costs before provisioning | Step 15 |
| Web | Read-only financial data, no persistent financial cache by default | Step 19 |
| Encryption | App-private database, platform storage protection, backup exclusions, optional system-authenticated app lock; no claim of application-level database encryption | Step 0 threat model; revisit before broader distribution |
| Portable backup | User-initiated, versioned JSON; plaintext clearly disclosed. Encrypted portable backup is a separate enhancement | Step 8 |

The user will connect their phone for testing. The currently connected test device is an A063 running Android 15 / API 35; do not record its serial number. Initial parser packages, final application ID/signing owner, Firebase owner/region, and release destination remain unresolved until their dependent steps.

### Explicit non-goals and later work

- No bank credentials, payment initiation, direct UPI transfers, live bank-balance claims, bank API integration, investment/tax advice, or investment portfolio tracking.
- No multi-currency conversion, shared household accounts, split transactions, receipt OCR, loan amortization, or server-generated forecasts in v0.1.
- Custom budget start days, rollover, recurring commitments and reminders are deferred. Recurrence must first have its own model, editing UX and tests; a web chart must not introduce it implicitly.
- AI Q&A and SMS import are optional tracks in section 10. Web editing is deferred until a separately tested editing UX uses the same conflict protocol.
- No PostgreSQL, ASP.NET service, generalized event bus, microservices, or extra Gradle modules without a concrete need. A small trusted cloud deletion component may be necessary in C; it is not an excuse to build a general backend.

### Main user journeys

1. Open the app without sign-in → create a cash/bank/card account → add an expense → see spending and remaining budget → restart without losing it.
2. Record salary, a transfer, a card purchase/payment, and a partial refund → understand each account's estimated position without double counting spending.
3. Select a CSV → map and preview → fix ambiguous rows → import once → re-import safely → undo eligible imported rows.
4. Export a backup → restore into an empty local profile → obtain the same records, budgets and totals.
5. Optionally enable supported notification detection → review a candidate → approve or dismiss → see exactly one financial effect.
6. Optionally enable sync → understand what will upload → use the same owner's data on another device/web → resolve conflicts and delete cloud data reliably.

## 2. Financial contract — shared by every client and import path

### Money and transaction structure

- Store money as signed 64-bit integer minor units in Kotlin/Room. Transaction `amountMinor` is strictly positive; direction comes from `kind`, not a negative amount. Opening balances and adjustment effects may be signed. Zero-value transactions are rejected; a zero budget is allowed.
- Product input ceiling: `1,000,000,000,000` paise per transaction/budget and the same absolute ceiling for an opening balance. This is a deliberate validation bound, not a bank limit. Parse decimal strings exactly; reject more than two decimal places and scientific notation. Never round silently or route money through floating point.
- Use checked integer arithmetic for totals. JSON encodes minor units as base-10 integer strings. Firestore stores bounded integer fields; TypeScript validates safe integers before converting to `bigint` for arithmetic. Overflow/unsafe values produce an error, never a rounded financial result. Floating point is permitted only for chart geometry after exact totals have been calculated.
- One transaction is one atomic financial event. Fields include immutable UUID, `kind`, amount/currency, primary account, optional transfer destination, optional refund link, optional adjustment direction/reason, effective date, optional event instant, category, merchant, notes, provenance, local creation/update metadata, revision and deletion state. Metadata clocks do not decide financial conflicts.
- Kinds: `EXPENSE`, `INCOME`, `TRANSFER`, `REFUND`, `ADJUSTMENT`. A transfer contains both account IDs in one record; do not create two unrelated financial records. Fees are separate expenses. Each expense/refund has one category; `Uncategorized` is valid.
- Only committed, non-deleted transactions affect totals. Import drafts, notification candidates, failed/pending payments, forecasts and dismissed items never do. Keep candidate status separate from transaction kind.

### Account effects and metrics

Account balances use a signed economic position: asset holdings are positive; bank overdrafts and credit-card debt are negative. A card overpayment can be positive. The UI may display a negative card position as a positive **Amount owed**, with the sign conversion tested.

| Kind, amount `a > 0` | Effect on account position | Expense/income reporting |
|---|---|---|
| Expense | Primary account `−a` | Gross spending `+a` |
| Income | Primary account `+a` | Income `+a` |
| Refund/reimbursement of a recorded expense | Primary account `+a` | Refunds `+a`; net spending `−a`; no income |
| Transfer | Source `−a`, destination `+a` | No spending or income |
| Adjustment | Primary account `+a` or `−a` as explicitly selected | Excluded from spending/income; shown separately |

- Account position = opening position + all committed account effects from the opening date through the selected date. The opening position is immediately before events on `openingDate`. Earlier entries require an explicit earlier opening date/balance correction; never silently exclude them from the displayed balance.
- A transfer requires two distinct, active, owned accounts in the same currency and valid opening dates for both. Credit-card bill payment is a bank-to-card transfer; card purchases/interest/fees are expenses. A credit notification alone is not evidence of income.
- Total tracked position = sum of signed account positions. Label it **Estimated tracked balance**, not verified bank balance or complete net worth. A transfer preserves this sum.
- A user-entered statement balance is a separate `BalanceObservation` with amount/date/source and label **User-reported balance as of …**. In v0.1 this means the statement's end-of-day balance; compare it with the estimated balance after that day's events. Recording it does not rewrite history. Only an explicit adjustment changes the ledger. Provider-verified balances are out of scope.
- Gross spending = sum of expenses. Refunds = sum of refunds. Net spending = gross spending − refunds. Income excludes transfers, refunds and adjustments. Recorded surplus = income − net spending; it is not bank cash balance. Show adjustments separately when reconciling the change in tracked balances.
- Linked refunds require an existing, non-deleted expense in the same ledger/currency. Partial refunds are allowed; total linked refunds cannot exceed the original expense. The receiving account may differ. Preserve the original category as a snapshot unless the user explicitly corrects the refund.
- An unlinked refund requires manual confirmation and a category; do not invent a purchase link. A reimbursement offsets a linked recorded expense; unrelated incoming money is income only after classification. Deleting or reducing an expense with linked refunds requires resolving those links atomically first.
- Changing kind, account, amount or date recalculates all affected balances/reports. Deletion is a tombstone with undo; archived accounts/categories remain available for historical display and corrections that retain their existing references. New entries cannot select archived records. Never cascade-delete historical transactions when archiving an account/category. Do not hide an archived account's historical effects or nonzero position from totals.

### Dates, budgets and reports

- Store `effectiveDate` as a ledger-local calendar date. For timed events, also store the UTC `occurredAt` instant and original zone/offset; derive the date using the ledger reporting zone. Date-only statement rows have `occurredAt = null` and precision `DAY`; do not invent a timestamp. `receivedAt` is capture metadata, not automatically the transaction date.
- Use `[startDate, endDate)` ranges. November means 1 November inclusive through 1 December exclusive in the ledger zone. Changing the phone timezone must not move recorded entries into another month. Changing the ledger reporting zone is deferred to an explicit migration.
- Actual transactions cannot have an effective date later than today in the ledger zone; future items need a later forecast feature. Backdated edits and corrections update the affected historical reports; starting a new month alone does not change old data.
- A `Budget` is keyed by month plus optional category, with one active record per key. Overall and category limits are separate caps on the same spending; do not add category allocations to the overall budget. Category budgets need not sum to the overall budget.
- `remaining = limit − netSpending` for that budget's period/scope. Negative remaining means overspending. Refunds in a later month reduce that later month's spending, possibly below zero; remaining may exceed the limit. Show gross spending and refunds so this is understandable. Charts must handle negative net categories, not force them into an invalid pie chart.
- Limits are explicit per-month values. “Copy previous month's limits” creates new records; editing one month never silently changes others. No budget is different from a zero budget. No rollover, custom start day, or forecast affects v0.1 actuals.

### Golden fixture and expected results

All examples are synthetic. Maintain a machine-readable equivalent in `fixtures/ledger/` during implementation, with expected values stored independently of the calculation code.

| Sequence in November; all account openings zero on 1 November | Expected effect |
|---|---|
| Bank income ₹60,000 | Income ₹60,000 |
| Bank groceries expense ₹2,250.75 | Gross groceries ₹2,250.75 |
| Bank groceries refund ₹250.75 linked to that expense | Net groceries ₹2,000 |
| Bank → Cash transfer ₹1,000 | Spending/income unchanged |
| Card Shopping purchase ₹1,499 | Gross Shopping ₹1,499; card position −₹1,499 |
| Bank → Card payment ₹1,499 | Card position ₹0; spending unchanged |

Expected final positions: Bank **₹55,501**, Cash **₹1,000**, Card **₹0**; total **₹56,501**. Gross spending **₹3,749.75**, refunds **₹250.75**, net spending **₹3,499**, income **₹60,000**, recorded surplus **₹56,501**. A ₹5,000 Groceries budget has **₹3,000** remaining.

Additional fixtures: ₹0.01; input ceiling/overflow; two distinct identical-amount purchases; duplicate source evidence; missing category; leap day; India/UTC month boundary; December refund of a November purchase; negative month spending; card overpayment; adjustment reconciliation; deleted transaction; invalid refund link; date-only imports; category/account archival. Kotlin and TypeScript must use the same fixtures.

## 3. Architecture and data ownership

| Area | Decision |
|---|---|
| Android | Kotlin, Compose, Material 3, ViewModel and unidirectional UI state |
| Persistence | Room with schema export, foreign keys/indexes, atomic writes; Coroutines/Flow |
| Domain | Small pure Kotlin calculation/validation functions; injected clock for deterministic dates |
| Dependency wiring | Simple constructor injection initially; introduce Hilt only when wiring warrants it |
| Background work | Notification listener for delivered events; WorkManager for durable, deferrable queue/sync retries |
| Optional cloud | Firebase Auth + Firestore + tested rules; App Check as an additional control |
| Optional web | React + TypeScript + Vite; accessible charts with exact shared financial semantics |
| Tests | JVM domain tests; Room/instrumented and Compose tests; Firebase rules/emulators; web unit and browser tests |
| Telemetry | Off initially; privacy-reviewed crash reporting only if later justified |

Android UI reads Room only. Domain operations validate and commit through repositories. Cloud data enters through the sync coordinator into Room; it must not create a second competing Android UI data source. Cloud is the shared revision authority when enabled, and the web reads accepted cloud records. Neither platform maintains independently editable balance totals.

Start with one Android app module at `apps/android/` and package boundaries for `domain`, `data`, `ui` and platform integrations. Extract modules only for a demonstrated build/test boundary. Preserve existing repository files and license. Root Gradle commands will use `:apps:android` if that is the module path actually configured; record real commands rather than hypothetical ones.

Create `apps/web/` in D, `firebase/` in C, and any trusted deletion component only when C requires it. `docs/` holds requirements, decisions, threat model, verification log, release checklist and data-format contracts. `fixtures/` contains synthetic data only. Do not scaffold unused services in A.

### Data model and timing

| Entity | Purpose and constraints | Introduced |
|---|---|---|
| LedgerProfile / Settings | Local UUID, currency, reporting zone, format versions; one active profile initially | A |
| Account / BalanceObservation | Type/name, signed opening position/date, archive state, optional masked account hint; optional reported balance | A |
| Transaction | Single atomic event using section 2; stable ID and local revision | A |
| Category / Budget | Stable IDs, archive state; unique month/scope budget key | A |
| SourceEvidence / ImportBatch | Minimal provenance, source key, linked transaction, batch status; no raw message body | A |
| LocalChange | Minimal prior revision for undo/merge reversal; private data with the same protection as the ledger | A |
| Candidate / MerchantRule | Normalized candidate, parser/rule version, review decision and rule priority | B |
| SyncOutbox / SyncState / Conflict | Owner/ledger generation, op ID, base remote revision, exact payload, retry state, unresolved variants, pull cursor | C |

Do not equate local profile IDs with Firebase UIDs. In C, a profile is explicitly bound to one UID and cloud ledger generation. Separate Room databases per profile simplify isolation; do not share a global unscoped transaction table between users. Sync metadata is not part of user-editable financial fields.

Account hints are optional bank names/masked suffixes, never full account/card numbers. Hints are not unique identifiers: ambiguous matches require user selection. `Uncategorized` has a reserved stable ID and cannot be archived. User categories can be added, renamed and archived; historical IDs remain stable. Renaming does not alter transaction identity or totals.

### Local safety from the first usable build

- Threat model covers accidental logging, malicious imports, unintended device backups, shared-device access, cross-user cloud access, lost devices, replayed notifications, interrupted writes and deletion resurrection. Rooted/compromised OS protection is not promised.
- Use private app storage; no broad storage permissions, bank credentials, external AI, analytics SDK or sensitive debug logs. Do not render untrusted merchants/notes as markup or executable content.
- Add optional system biometric/device-credential app lock, lock on cold start and after a configurable background timeout (default 30 seconds), and an optional sensitive-screen/task-preview shield. A UI lock is not database encryption. No custom PIN cryptography in MVP.
- Set explicit backup configuration for supported Android versions, covering database, preferences, files, keys and device transfer. Do not rely only on `allowBackup=false`: Android documents manufacturer-dependent transfer behavior. Test configured exclusions on supported devices. [Android backup behavior](https://developer.android.com/identity/data/autobackup)
- Room is not claimed to be application-encrypted. Reassess a maintained encrypted SQLite solution only if the threat model needs it; require migration, performance and key-recovery tests before adopting it. Keep secrets in appropriate platform-backed storage and out of exports.
- Release/demo data must never mix: use a separate debug/demo profile with a visible label. Development artifacts, fixtures, screenshots and logs must contain no real financial data.

## 4. Import, detection and correction contract

### CSV in v0.1

1. Use the system document picker. Support UTF-8 CSV, documented quoting/newline/BOM handling, maximum 10 MiB and 10,000 rows initially. Validate without loading unbounded input or retaining the original file after processing.
2. Publish a generic format: `date,amount,direction,merchant,category,reference`. Date defaults to ISO `YYYY-MM-DD`; amount is positive INR decimal; direction is `DEBIT` or `CREDIT`; optional fields may be empty. Also support explicitly mapped debit/credit columns, requiring exactly one positive amount per row. Any alternate date format is selected explicitly; never guess `03/04`.
3. User chooses the account, mapping and date format. Preview parsed, invalid, duplicate and review-required counts plus debit/credit totals. Date-only rows retain day precision. Negative/signed bank formats require an explicit adapter; no automatic sign guessing.
4. Debit/credit is a balance direction, not an expense/income classification. Credit/refund/transfer ambiguity requires review, and the user confirms any bulk expense classification. Cross-account transfer evidence must attach to one transfer, not become two expenses.
5. Fingerprint the profile, account, file bytes and mapping version for replay protection; also remember the original file identity so remapping an already committed file cannot silently add it again. Within a batch, retain row position/occurrence so identical legitimate rows survive. A different/overlapping file uses reference matching or duplicate suggestions; a content hash alone cannot resolve overlapping statements.
6. Preview/cancel writes no transactions. Commit the accepted subset and provenance atomically with an explicit count; invalid/unresolved rows remain excluded. Disk failure/process interruption cannot leave a half-committed batch.
7. Batch undo deletes only transactions created by that batch whose revisions/links remain unchanged, and removes that batch's evidence from pre-existing records. Modified or newly linked rows require review. Preserve replay/undo status so re-import offers explicit restore instead of silently resurrecting deleted entries.
8. A transaction CSV export is a report, not a full backup. Escape spreadsheet-formula prefixes in text cells and quote fields; make any safety transformation explicit. JSON backup preserves exact original values.

### Notification candidates in v0.2

- Consent explains the breadth of notification access, local filtering, limited capture coverage and disable/revoke paths before opening Android settings. Allowlist user-selected, supported package IDs; never collect all notifications into a database.
- Notification access and permission to post FinanceGenie's own notifications are different features. Detection must not depend on the latter; request posting permission only if the user enables app alerts. [Notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [notification posting permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- Filter and parse in memory, then persist only the normalized candidate and minimal provenance: source package/event key, received time, supported event time, reference/account hint if present, amount/type proposal, parser version and reason flags. Never retain or upload raw bodies, OTPs or balances from unrelated messages. Sensitive references remain private, never analytics identifiers.
- Missing financial fields cannot be invented. Unsupported/ambiguous transaction-like events produce a minimal review item if safe to extract; otherwise discard content and show only a sanitized unsupported-event count. Ads, OTPs, summaries, failed/pending payments and balance-only events create no financial record.
- Source event replay identity must distinguish an updated notification from an unchanged repost; use the source key plus normalized event payload/version, not notification ID alone. A later reversal or changed amount is a reviewable revision/event, not an overwrite.
- Review-first is the default. Auto-post can be enabled per tested adapter only when amount, date, account and kind are resolved, the payment is final, and duplicate checks pass. Confidence is a deterministic reason/status, not an uncalibrated percentage or guarantee. Category may remain Uncategorized.
- Durability begins only after the normalized queue entry commits. Process death before that, force-stop, revoked access, work-profile/device restrictions or missing source notifications can cause missed captures. Show access/connection state and offer CSV/manual reconciliation; do not promise complete history or instantaneous delivery. [Listener restrictions](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- Dismissed candidate payloads expire after 30 days; minimal replay keys remain until local data deletion. Pending candidates show age and a clear-all action. Source evidence attached to a committed transaction follows the ledger retention policy. Raw notification content has no retention period because it is not persisted.

### Duplicate handling and merchant rules

- Separate **same-source replay** from **two sources describing one transaction**. Source replay is idempotent. Cross-source automatic linking needs a validated reference namespace plus compatible resolved account/direction/amount and documented date window. A reference string is not globally unique across all banks or transaction sides.
- Conflicting reference evidence goes to review. Same amount/time/merchant without a trustworthy reference produces a suggestion, never automatic merging. Distinct trustworthy references remain distinct even if every other field matches. Manual entry always creates a deliberate event; later evidence may be attached after review.
- Bank and payment-app notifications become one expense only when the strong-match conditions hold. Without those conditions, hold the possible duplicate in review. Prefer a visible review task over silently losing a legitimate purchase or inflating confirmed totals.
- Merging retains both provenance records and prior revisions, selects one canonical transaction and tombstones any already committed duplicate atomically. “Undo merge” restores the prior transactions/evidence after validating intervening edits. This is not split-transaction budgeting.
- Rule precedence: explicit transaction correction → user merchant rule → tested built-in rule → Uncategorized. Resolve ties by explicit priority and stable rule ID. Rule creation affects future candidates by default; historical reclassification needs an explicit preview. Normalization must not merge distinct merchants merely because of punctuation.

## 5. Optional cloud contract — prove before enabling real-data sync

Firestore's offline persistence uses last-write-wins for competing changes. It is not the financial conflict policy. Use Room as the durable offline queue, configure Firestore to avoid a second persistent financial cache, and apply online revision checks. Firestore transactions fail offline and may retry their callback; callbacks must have no external side effects. [Offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline), [transaction behavior](https://firebase.google.com/docs/firestore/manage-data/transactions)

### Ownership and authorization

- Path convention: `users/{uid}/ledgers/{ledgerId}/...` for accounts, transactions, categories, budgets, operation receipts and conflict records. A user control document carries the active ledger generation and lifecycle state. Initial cloud scope is one ledger per user, with multiple devices.
- Use deterministic budget document keys derived from month/category scope so two devices cannot create two caps for the same logical budget. For strong source identities, claim a deterministic owner-scoped matching key atomically with the financial event; a pre-existing compatible claim links evidence, while incompatible evidence requires review. Test two devices importing/capturing the same referenced payment concurrently. Weak matches remain suggestions; do not impose uniqueness on amount/time/merchant.
- Rules default deny. Validate authenticated UID, active generation, allowed fields/types/lengths, bounded integer money, immutable ownership/IDs, kind-specific shape, revision transitions, referenced records and document size. Validate coupled writes such as refund-link changes with atomic-write rules. A read-only web UI does not replace rules.
- Rules cannot generally prove every aggregate invariant across arbitrary rows. Prototype concurrency-sensitive constraints, including competing partial refunds, in Step 15; use a small transactionally updated invariant record if needed. If safe client transactions/rules cannot enforce the contract, document the need for a narrow trusted operation before proceeding. Never claim that shape checks prove ledger correctness.
- Test anonymous access, A/B users, forged ownership, invalid/replayed updates, archived/deleted references, bad revisions and deletion-state writes. Admin SDK bypasses rules; any trusted service needs its own authentication, authorization and least-privilege IAM. [Rules testing](https://firebase.google.com/docs/firestore/security/test-rules-emulator)
- App Check complements authentication and authorization; it does not replace either. Validate provider behavior for private sideload and Play-installed builds before enforcement; emulator/debug tokens stay in development. [App Check](https://firebase.google.com/docs/app-check)

### Identity transitions

- Before first upload, display the account identity, data categories and record count. Bind a local profile explicitly to that UID/generation. If cloud data already exists, default to opening it as a separate profile; never silently merge an existing local ledger into it.
- Sign-out cancels workers/listeners and hides the bound profile. Keep its unsynced data in the isolated database for the same account to recover; show the unsynced count and provide export/discard choices before sign-out. An unrelated local-only profile may remain usable. Do not reassign the bound profile to the next signed-in user.
- Turning sync off while remaining signed in pauses uploads/downloads but preserves local use. Cloud data remains until a separate delete action. Re-enabling sync reconciles revisions before uploading pending edits.
- Losing network/authentication never blocks local editing. Switching owners must close database handles, clear UI/in-memory caches and cancel owner-scoped work before showing another profile.

### Synchronization protocol

1. Commit the local financial change, revision, provenance and outbox intent in one Room transaction. Outbox payloads become immutable once sent; serialize pending operations per entity. An operation records `opId`, owner/generation, entity ID, base remote revision and payload/schema version.
2. Online, run a Firestore transaction that first checks lifecycle/generation and a durable operation receipt. If the receipt exists, return its result. Otherwise compare the remote revision with the operation's expected base revision; a new entity expects absence.
3. On a match, write revision `n+1`, server update time and the operation receipt atomically. A retry after the server accepted a write but before local acknowledgement returns the same result. Never use the device wall clock to choose a winning financial edit.
4. On a mismatch, preserve local/base/remote versions as a conflict; do not overwrite the accepted cloud version. Android shows one local version plus a visible conflict marker, not both as transactions. Web shows the accepted cloud version with sync freshness. Warn that totals can differ until resolution. A user-selected resolution is a new operation against the latest revision.
5. Coalesce only unsent intents. Edits made while an operation is in flight stay queued; acknowledgement updates sync metadata without replacing newer local edits. Test this separately from two-device conflicts.
6. Pull dependencies before transactions. Use a documented cursor ordered by server update time plus document ID, with an overlap/re-read strategy for equal timestamps and pagination races; deduplicate by entity revision. Commit changes and cursor atomically in Room. Listener callbacks and retry overlap must be harmless.
7. Delete uses a revisioned tombstone. Retain cloud tombstones and operation receipts for v0.3 until a tested compaction/device-generation protocol exists; avoid a premature fixed TTL that lets old devices resurrect data. Unresolved delete-vs-edit conflicts remain explicit.
8. Keep one transfer in one cloud document. Operations that must change several records together, such as a linked-expense correction or duplicate merge, commit atomically as a group with revision checks. Import batches synchronize resumably; show progress/partial-cloud state until complete rather than promising whole-file cloud atomicity.
9. Retry transient network/quota errors with bounded exponential backoff; authentication failure pauses for re-authentication, validation failures need correction, unsupported schema needs an upgrade. Show pending/conflict/error/last-success state. Never retry malformed writes forever.
10. Test full bootstrap and incremental catch-up. Default queries load the current period with explicit history pagination; account opening/history calculations must not label a partial subset as a complete balance. Record read/write counts before expanding listeners.

### Data lifecycle and operational costs

- Distinguish: remove this device's copy; stop sync; delete cloud ledger while preserving an explicitly detached local copy; delete the account and cloud data. Each action states its scope. Unsynced local work must not be silently discarded.
- Cloud deletion requires an authenticated, resumable workflow: mark the generation deleting and reject ordinary writes; purge all financial collections, receipts/conflicts and descendants; verify completion; remove Auth identity last for account deletion. Keep only the minimal non-financial deletion control state needed to reject stale clients, with a documented retention policy. Firestore parent deletion does not delete child collections; production collection deletion should run in a trusted environment. [Firestore deletion guidance](https://firebase.google.com/docs/firestore/manage-data/delete-data)
- A server-side deletion job or equivalent trusted service is therefore a C integration decision, not an assumed free feature. Prototype with emulators; choose the smallest reliable deployment. Do not enable real-data sync if deletion depends on an unimplemented client loop or on the phone staying open. Local-only A remains releasable if this dependency is unavailable.
- Reconnecting devices cannot recreate a deleted generation. Surviving offline copies cannot be remotely erased while offline; disclose this, wipe/hide them on the next authorized deletion-state check, and require explicit new-generation import for any intentional recovery. Restored backups never automatically resume old outbox work.
- Define what sync uploads: committed financial entities, necessary source labels/matching keys, rules/settings and conflict variants. Exclude raw messages, candidate queue, local-only private notes, full bank identifiers, local change history, debug diagnostics and credentials. Matching keys are private financial metadata, not anonymous analytics identifiers. Mark notes as **On this device**; preserve them when cloud fields update. A future notes-sync feature requires separate consent/schema work.
- Estimate reads, writes, storage, attestation, hosting and deletion-job costs for one and 100 users from measured operations. Verify actual quotas/provider requirements before provisioning. No paid upgrade without authorization; billing alerts are not spend caps. Avoid promising Spark supports every later component. [Firebase pricing plans](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans)

## 6. Implementation sequence and verification gates

**Current authorized scope is milestone A, Steps 0–9.** See `progress_tracker.md` for step status/tradeoffs and `docs/verification-log.md` for evidence. For each step: state scope → implement → run applicable checks → record evidence/tradeoffs → advance along satisfied dependencies. An unavailable device, credential or deployment blocks the affected check/step, not independent work. Pure JVM domain work may proceed alongside pending foundation manual device checks, with those checks still required at Gate A. The user will connect a phone when device testing is needed. A release gate never passes with a mandatory check unrun.

### Phase A — Offline Android

#### Step 0 — Freeze the offline contract and risks

**Deliver:** `docs/product-requirements.md`, `docs/decisions.md`, `docs/security-threat-model.md`, initial `docs/e2e-acceptance.md`; copy decisions from this plan by reference where possible. Confirm phone baseline, screens, data definitions and deferred scope.

**Verify:** Walk through the six section 1 journeys and golden fixture; map every promised feature to a step or deferral; record the initial threat controls and proposed performance device. Gate: no unresolved ambiguity in money, dates, transfer/refund treatment or v0.1 scope. This is a documentation gate; no fictional build evidence is required.

#### Step 1 — Reproducible project, CI and privacy defaults

**Depends on:** 0. **Deliver:** Minimal Android project, pinned compatible toolchain/version catalog and wrapper, build README, formatting/lint and JVM CI, secret scanning, debug/demo isolation, manifest and backup exclusions. Preserve repository/license; allow committing the required Gradle wrapper even if an inherited wildcard ignores JAR files.

**Verify:** Clean checkout builds, JVM tests/lint/format checks run, app installs/launches on emulator; merged release manifest contains only required permissions and no SMS permission. Inspect backup configuration and generated artifacts for secrets. Record actual module paths and commands. Gate: reproducible minimal app with no data-collection SDK or launch-time permission prompt.

#### Step 2 — Pure financial model and golden fixtures

**Depends on:** 1. **Deliver:** Section 2 types, input validation and pure account/budget/report functions; injectable clock; golden JSON fixtures with integer-string amounts.

**Verify:** All exact fixture results, overflow/input rejection, linked refund limits, card overpayment, transfer conservation, adjustments, deletion and month-boundary behavior. Test that adding a transfer cannot change net spending, deleting then undoing restores totals, and fixture ordering does not change results. Gate: independently calculated expectations match exactly.

#### Step 3 — Room persistence and atomic operations

**Depends on:** 2. **Deliver:** A entities/DAOs/repositories, schema export, indexes for date/account/category, unique budget/source keys, foreign-key rules, atomic edits and local undo history. No cloud schema scaffolding yet.

**Verify:** Instrumented insert/update/delete/undo/restart tests, rollback on failed grouped writes, duplicate ID rejection, historical archive behavior and large amounts. At initial schema v1, validate schema export/reopen; migration tests become mandatory on the first real schema change and for every shipped upgrade path. Never invent a v2 solely to claim migration coverage or use destructive fallback. [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)

#### Step 4 — Accounts, manual transactions and accessible navigation

**Depends on:** 3. **Deliver:** Overview, Transactions, Budgets, Settings; Accounts and category management within Settings/transaction selection. Create/archive accounts, opening positions, reported-balance observations; add/rename/archive categories; add/edit/delete/undo expense/income/transfer/refund/adjustment; search/filter and source labels. Add system app lock and privacy shield.

**Verify:** Add ₹249.50 Food expense → edit to ₹199.50 → delete/undo → restart; rapid double-submit creates one record. Complete golden fixture through UI, test invalid/missing inputs, linked-refund edits, card display and account archive. Test empty/error states, rotation, process recreation, dark mode, 200% font and TalkBack. Verify app lock/recent-app preview behavior on the test device. Gate: real offline CRUD and no false verified-balance labels.

#### Step 5 — Calendar budgets

**Depends on:** 4. **Deliver:** Overall/category budgets, month selector, explicit copy-to-next-month action, remaining/overspent states; in-app warnings only initially.

**Verify:** ₹5,000 limit − ₹4,000 spending = ₹1,000 remaining; another ₹1,500 = ₹500 over. Test later-month refund, negative net spend, zero/no budget, backdated edit, independent overall/category limits, copy without historical mutation and transfers excluded. Gate: UI and domain calculations agree.

#### Step 6 — Offline overview and analytics

**Depends on:** 5. **Deliver:** Gross/net spending, income, refunds, recorded surplus, estimated tracked balances, category bars, daily/monthly trend, recent transactions and consistent filters. Prefer small native chart components; include text/table alternatives.

**Verify:** Golden fixture and empty/single/1,000-row data, negative categories, long labels and account/date filters. Confirm all surfaces converge to correct results after an edit; do not assert an arbitrary number of UI recompositions. Measure baseline startup/query/render times. Gate: accurate offline analytics with accessible non-chart equivalents.

#### Step 7 — Previewed, reversible CSV import

**Depends on:** 6. **Deliver:** Section 4 CSV contract, synthetic sample, mapping/preview, classification review, idempotent atomic commit, batch history and safe undo. Implement the CSV/manual subset of duplicate matching now; Step 12 extends it to notifications rather than delaying safe CSV behavior until B.

**Verify:** A 100-row fixture with quoted commas/newlines, identical legitimate rows, ambiguous dates/credits, transfers, duplicates and invalid rows; exact accepted/skipped/review counts and totals. Re-import unchanged file, changed mapping, reordered file and overlapping file. Kill during commit, cancel preview, simulate storage failure and undo after editing a row. Test size/row caps. Gate: no silent loss, doubled expenses or partial committed batch.

#### Step 8 — Backup, restore, export and local deletion

**Depends on:** 7. **Deliver:** Versioned full JSON backup via document picker, separate safe CSV report export, restore to an empty new local profile, integrity validation and local delete-all. Include accounts, observations, committed/tombstoned transactions, categories, budgets, necessary provenance/replay keys and local notes; add rules when B exists. Exclude credentials, device permissions, pending candidates, sync sessions/outbox and disposable undo history. Schema version and per-entity counts are mandatory; checksum detects accidental corruption, not authenticity.

Restore preserves financial IDs but creates a fresh local profile identity and remaps profile-scoped replay keys consistently. Never restore owner bindings or resume cloud uploads automatically. Initial backup input limits are 50 MiB and 100,000 transactions, with bounded per-field/entity counts validated before writes; increasing limits requires a recovery/performance check.

**Verify:** Export/restore 1,000 synthetic records with linked refunds/transfers; IDs, fields, totals and budgets match. Reject future unsupported schema, malformed/oversize input, invalid references and truncated files without changing the existing profile. Stage/validate before making the restored profile active; crash cannot destroy the old profile. Plaintext export requires an informed user action; test formula-safe CSV text. Delete-all clears profile files/queues and leaves exports at their user-selected location with clear disclosure. Gate: a fresh-install recovery drill passes before personal financial use.

#### Step 9 — Offline acceptance and Gate A

**Depends on:** 0–8. **Deliver:** Release-candidate APK, current README, known limitations and verification report; no external publication implied.

**Verify:** Release-build smoke test with no network/sign-in/sensitive permission, golden journeys, backup/restore, local deletion, accessibility and the section 7 performance dataset. Scan logs, APK permissions and backup behavior; verify demo data is absent. Gate A: useful, accurate, recoverable offline app. Notification, Firebase, web and AI dependencies are not required.

### Phase B — Notification detection

#### Step 10 — Supported formats and parser fixtures

**Depends on:** A; B must be in scope. **Deliver:** Named package/format support matrix, bounded pure parser, synthetic corpus, normalized candidate contract and reason flags. Do parser work before connecting real notification access.

**Verify:** Debit/credit, successful/failed/pending payment, reversal, multiple amounts, missing account/reference/date, Indian digit grouping/decimal notation, OTP, marketing, balance-only, updated notifications and unsupported formats. Gate: supported positives parse exactly; negative/ambiguous fixtures never create committed expenses.

#### Step 11 — Consent and minimal durable capture

**Depends on:** 10. **Deliver:** Feature onboarding, package allowlist, access-settings flow, listener state/disable control and normalized candidate persistence. Keep callbacks responsive; no polling loop or blanket battery-exemption request.

**Verify:** Synthetic notifications from a debug-only supported fixture app reach the parser on a device; revoke/toggle-off stops processing; unrelated apps are ignored. Test listener reconnect, unsupported device/profile state and process interruption. Inspect storage/logs to confirm raw bodies are absent. Gate: consent and actual capture verified, with honest coverage limitations.

#### Step 12 — Replay protection, duplicate linking and correction

**Depends on:** 11. **Deliver:** Section 4 matching rules across CSV/manual/notification evidence; conflict review; atomic merge and undo-merge. A unique source key and ledger operation must commit together.

**Verify:** Two sources with a valid shared ₹450 reference → one expense; distinct ₹450 references → two; same amount/time with no reference → review, no automatic merge. Repost, changed notification, deleted/undone import replay, process death and manual-to-import matching remain idempotent. Gate: both duplicate inflation and false merges are covered.

#### Step 13 — Review inbox and merchant rules

**Depends on:** 12. **Deliver:** Approve/edit/dismiss, source/reason details, candidate aging/clear controls, rule priority and opt-in adapter auto-post after qualification. Manual corrections always win.

**Verify:** Candidate dismissal has no financial effect; approval posts once; ambiguous account/credit stays in review; new rule does not silently recategorize history; explicit reclassification preview matches applied changes. Test pruning and replay after dismissal. Gate: uncertainty can always be resolved without editing hidden state.

#### Step 14 — Reliability and Gate B

**Depends on:** 10–13. **Verify:** Payment fixture → candidate → one transaction → category/budget update on physical test hardware, including access denial/revocation, process death after queue commit, reboot, airplane mode and source-app format mismatch. Record idle battery comparison and background behavior; disclose pre-queue capture loss. Gate B: supported paths work and fallbacks remain usable; emulator-only parser success does not prove OEM notification reliability.

### Phase C — Optional synchronization

#### Step 15 — Emulator protocol, rule and cost feasibility

**Depends on:** A; C must be in scope. **Deliver:** Cloud schema/rules/indexes, protocol decision record, two-client emulator harness, lifecycle/deletion design and operation-cost worksheet. This is a focused risk prototype, not production integration.

**Verify:** Revision conflict, acknowledgement-loss replay, grouped-write invariants, concurrent refunds, duplicate budget creation, cross-device source claims, tombstone resurrection and A/B user rule tests. Validate deletion-job deployment requirements and sideload/App Check strategy. Gate: section 5 can be enforced and tested; record any needed small trusted component and its cost before provisioning. If infeasible, revise C explicitly while keeping A usable.

#### Step 16 — Identity, opt-in and isolated profiles

**Depends on:** 15. **Deliver:** Separate development/production Firebase configuration, current supported Google authentication integration, consent screen, explicit profile binding, owner-scoped workers and sign-out/re-authentication UX. Emulator identities are sufficient for rule tests; actual OAuth requires configured test-project/device verification.

**Verify:** Offline local-only use, first upload consent, pre-existing cloud/local collision, A→sign-out→B switch, A's unsynced edits retained without B exposure, auth expiry and sync pause/resume. Gate: identity transition tests pass; do not claim emulator authentication proves production Google sign-in.

#### Step 17 — Outbox, conflict resolution and incremental sync

**Depends on:** 16. **Deliver:** Complete section 5 protocol, status UI, bounded retries, schema negotiation, bootstrap/pagination, conflict resolution and local-notes preservation.

**Verify:** Offline create/edit/delete, duplicate retries, server-accepted/lost-ack crash, edit while upload in flight, two-device edit/edit and edit/delete conflict, equal-time pagination, interrupted bootstrap/import, missing dependency, quota/auth errors and unsupported schema. Gate: no lost acknowledged edits, hidden conflicts or duplicate financial effects; shared fixture totals match after convergence.

#### Step 18 — Deletion, recovery and Gate C

**Depends on:** 17. **Deliver:** Trusted resumable deletion, lifecycle-state rules, device-copy/cloud-only/account deletion UX, recovery documentation, deployed configuration checks and measured usage.

**Verify:** Abandon/retry deletion mid-job; purge descendants/receipts/conflicts; Auth deletion ordering; reconnect stale device; restore pre-deletion backup; switch identity during work; confirm local-only notes never upload. Use a configured test project as well as emulators for OAuth/App Check/deployment behavior. Gate C: real-data sync remains disabled until authorization, isolation, conflict, deletion and recovery checks pass. Billing or service-access blockers do not invalidate A/B.

### Phase D — Private web dashboard

#### Step 19 — Authenticated read-only web

**Depends on:** C; D must be in scope. **Deliver:** React/TypeScript app, scoped queries, sign-in/out, loading/error/offline/conflict-freshness states, responsive navigation, read-only transactions/budgets. No service-worker financial caching; use memory-only Firestore data cache and explicit session persistence policy.

**Verify:** Direct anonymous/cross-user SDK calls denied; URL/client tampering cannot expose another UID; sign-out and account switch clear financial UI/cache/listeners, including browser-back navigation. Test mobile/tablet/desktop and keyboard use. Gate: authorization works independently of route guards.

#### Step 20 — Shared analytics, cost checks and Gate D

**Depends on:** 19. **Deliver:** Category/trend/account/budget views, paginated search/filter and safe report export. Use exact TypeScript money helpers and the same fixture contract as Kotlin; no unimplemented recurrence widget.

**Verify:** 12-month golden dataset produces identical totals in both clients, including refunds/transfers/adjustments/month boundaries and negative categories. Test query pagination, stale/partial-data labeling, logout cleanup and accessible chart alternatives. Record query/index/read counts; no unbounded history listeners. Gate D: private dashboard is ready for an authorized deployment after these checks, not publicly readable financial data.

### Phase E — Public release of the selected scope

#### Step 21 — Release hardening and full acceptance

**Depends on:** Gate A and any included B–D gates. **Deliver:** Complete CI matrix, migration/backup upgrade drill, dependency/license/secret checks, accessibility/performance report, release candidate and privacy-safe diagnostics. Add crash reporting only after explicit data-content review.

**Verify:** Run section 8 on a clean checkout and release-like build. Test supported Android minimum/latest target environment and a physical device, 10,000-record performance, storage failure, lifecycle interruptions and applicable browsers/cloud rules. Inspect telemetry: no financial amounts/descriptions, notes, identifiers or tokens. Gate: all included critical scenarios pass; evidence predates submission, not follows it.

#### Step 22 — Distribution review and Gate E

**Depends on:** 21. **Deliver:** Accurate feature/permission inventory, privacy policy, Data safety and financial-features declarations where applicable, support/contact, signing/recovery ownership, release notes, rollout/rollback procedure and current Play policy review.

**Verify:** Release-signed artifact smoke test; installed/merged permissions match declarations; privacy/support URLs work; account-creating builds have in-app and web-request deletion paths with a tested completion process. [Google Play deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en)

Gate E: included gates, declarations and owner-authorized distribution are complete. Submission/publication requires the user's authorization if not already given; prepare all reviewable artifacts first. A release rollback must not require opening a newer Room schema with an incompatible old binary—document backup/forward-fix recovery.

## 7. Quality targets and evidence

Targets below are proposed engineering budgets, not measured results. In Step 0 record a representative physical device/model/OS and benchmark methodology. Use a release/profileable build with 10,000 synthetic transactions and fixed fixtures. Tighten or revise targets only with measurements and a recorded reason.

| Measure | Initial target | Evidence |
|---|---|---|
| Cold launch to usable offline Overview | p95 ≤ 2 seconds across at least 20 runs on the reference device | Startup benchmark; build/device specified |
| Save to visible updated transaction/budget | p95 ≤ 300 ms across at least 30 operations | Instrumented timing, local data |
| Monthly summary/search | p95 ≤ 300 ms for summary and ≤ 500 ms for paged search | Fixed queries and warm/cold cache notes |
| 10,000-row CSV import | ≤ 15 seconds, progress/cancel responsive before commit | Device timing and memory observation |
| Notification processing | p95 ≤ 500 ms from callback receipt to durable normalized candidate | Excludes OS/source delivery latency |
| Background work | No idle polling or continuously running worker; battery difference documented | Comparable idle observation with feature off/on |
| Accessibility | TalkBack labels/order, 200% text usable, non-color status cues, ≥48 dp Android touch targets, keyboard web access | Automated checks plus manual journey |
| Correctness/privacy | Zero known wrong totals, lost committed writes, unreviewed fuzzy merges or cross-owner access | Fixture, interruption and authorization suites |

APK size, memory peaks, frame timing and web bundle/query costs get an initial baseline; investigate material regression rather than invent an unsupported universal limit. A missing benchmark/device is `NOT RUN`, never a performance pass.

On 10 November 2026 the user accepted the measured budget Save-to-visible P95 of
314.52 ms (approximately 315 ms) for this individual-use app. Preserve that result
as an approved exception to the initial 300 ms budget target, without claiming the
original target passed. Expense-save and startup targets remain unchanged. Revisit
material regressions against the recorded budget baseline.

CI grows with the code: Android build/unit/lint/format from Step 1; Room/Compose instrumented tests when available; parser corpus in B; rules/protocol tests in C; TypeScript/browser tests in D. Run critical tests on every relevant change, full release acceptance before each release. Do not require unrelated web/cloud tests before those components exist.

## 8. End-to-end acceptance matrix

| ID | Scenario and expected outcome | Required for |
|---|---|---|
| A01 | Fresh offline install → account → expense → budget → restart; exact values persist | A+ |
| A02 | Golden salary/refund/transfer/card fixture; Bank ₹55,501 and total ₹56,501 | A+ |
| A03 | Edit/delete/undo and linked-refund correction preserve invariants and recalculate | A+ |
| A04 | Date-only/backdated/month-crossing refund and phone-zone change follow section 2 | A+ |
| A05 | CSV replay/overlap/identical real rows/cancel/crash/undo; exact accepted counts | A+ |
| A06 | Full backup → empty-profile restore → equal records/totals; corrupt restore preserves existing data | A+ |
| A07 | Local delete-all, app lock, backup exclusions, no sensitive logs and accessible empty states | A+ |
| B01 | Supported notification → review/approval or qualified auto-post → one expense | B |
| B02 | Same reference links evidence; distinct references preserved; fuzzy match held for review | B |
| B03 | Denial/revocation/reboot/durable-queue crash; fallbacks work and limitations are shown | B |
| C01 | Offline mutation → retry/lost acknowledgement → one cloud effect | C |
| C02 | Two-device edit/delete conflicts visible; resolution preserves chosen version without hidden loss | C |
| C03 | A/B switching, direct unauthorized requests and stale queued work never cross owners | C |
| C04 | Generation deletion, interrupted cleanup, stale device and restored backup cannot resurrect data | C |
| C05 | Incremental pagination/equal-time updates/partial bootstrap converge with exact totals | C |
| D01 | Android/web exact parity for 12 months; incomplete/stale cloud state is labeled | D |
| D02 | Auth/direct access/logout/cache clearing and responsive/keyboard workflows pass | D |
| E01 | Release-signed build, migration/recovery drill and measured quality budgets pass | Every external release, scaled to scope |
| E02 | Current declarations, privacy/deletion URLs, signing ownership and rollout plan match artifact | Play/public release |

No-go conditions: wrong money/date calculations; silent data loss; duplicate inflation or false merge; unresolved critical authorization/privacy bug; stale-client resurrection; unrecoverable storage upgrade; missing required export/deletion flow; inaccurate permissions/data claims. A known critical defect blocks the relevant release, even if other tests pass.

## 9. Progress and change control

Use `NOT STARTED`, `IN PROGRESS`, `PASS`, `BLOCKED` and `DEFERRED` for steps. Individual checks are `PASS`, `FAIL`, `NOT RUN` or `NOT APPLICABLE` with a reason. A step is `PASS` only when all mandatory checks pass; optional excluded features are `DEFERRED`, not fake passes. A blocked step may coexist with progress on an independent authorized step; do not bypass its release gate.

Maintain `progress_tracker.md` after every step and status change. It owns the current dashboard, dependencies and tradeoffs: decision, alternative, reason, consequence, revisit trigger and verification link. `docs/verification-log.md` holds detailed evidence and `docs/decisions.md` holds durable decisions. Record that the user provides the physical phone for device checks; preserve pending checks without storing device serials or private data.

For each implementation step append to `docs/verification-log.md`:

```text
Step / milestone / revision:
Implemented files and behavior:
Decisions and assumptions:
Automated checks: exact commands, environment, exit/result
Manual checks: device/browser/build, synthetic fixture, observation
Security/privacy and accessibility checks:
Evidence paths: test report, sanitized screenshot/log, benchmark
Unrun checks / blockers / known limitations:
Status: NOT STARTED | IN PROGRESS | PASS | BLOCKED | DEFERRED
Next dependency-satisfied action:
```

Document tests actually run; never fabricate device evidence or use code inspection as an integration test. Do not mark this planning review as application verification. Record changes to financial semantics, scope or data formats in `docs/decisions.md` once implementation begins, updating this plan and fixtures together. Avoid repetitive approvals for already authorized work; ask only for missing consequential decisions, credentials/access, spending or publication not already authorized.

### Rationale for this revision

- The two original documents were identical. One canonical plan plus an execution prompt prevents divergent copies.
- Account/domain rules precede UI and capture, so imports, reports and sync share a concrete financial model.
- Calendar budgets, refund timing, liability signs, date-only imports and exact cross-platform money representation are now explicit.
- Privacy, CI, backup/restore and deletion enter A; they are needed before real personal data, not only before a public release.
- Parser fixtures precede collection; strong replay identity and conservative duplicate review replace unsafe amount/time merging.
- Cloud protocol, owner transitions and trusted deletion have their own feasibility gate; free pricing, emulator OAuth and last-write-wins are no longer implicit assumptions.
- End-to-end checks precede release. Migration tests apply to actual upgrades, and optional dependencies cannot freeze unrelated milestones.
- Custom periods/rollover/recurrence and web editing are explicitly deferred instead of appearing in isolated steps without prerequisite models.

## 10. Optional tracks and revalidation

### SMS import — only if separately requested

Keep restricted permissions out of default/public manifests unless eligible and approved. Current Google Play documentation includes an SMS-based money-management exception, subject to review; this is neither guaranteed eligibility nor a blanket prohibition. Recheck policy and declarations at the time of work. Personal sideloading still requires Android permission/behavior validation. [Google Play SMS policy](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)

If pursued, isolate SMS capability in a build configuration, use the same candidate/parser/dedup pipeline, never collect OTP/non-financial content, and test denial/revocation plus SMS+notification duplicates. Inspect every Play-bound merged manifest and packaged artifact for restricted permissions from transitive libraries. A rejected/unavailable SMS path is deferred without blocking A–E.

### AI Q&A — only if separately requested after deterministic analytics

No external AI by default. A future narrow ASP.NET Core or other approved service may verify Firebase tokens, enforce owner access and use only authorized precomputed aggregates. Give a per-request data preview, provider/retention disclosure, bounded cost/rate limits and explicit opt-in. No bank credentials, raw messages, private notes or whole transaction histories by default; no ledger writes, payment actions or authoritative arithmetic.

Verify golden-query answers against the engine, deny cross-user requests, treat descriptions as untrusted data, test prompt injection, redact logs, and preserve normal app operation on provider failure. Label unsupported answers and uncertainty. Choose model/provider/pricing at that time; this plan does not authorize a paid dependency.

### Recheck external facts at the point of use

The official sources linked above were reviewed on 9 November 2026. They support platform constraints, not a claim of app compliance. At Step 1 verify the compatible toolchain; at C verify Firebase/Auth/App Check providers, region, SDK cache APIs, quotas and deletion hosting; at each public release verify current target-SDK, sensitive-permission, financial-features, Data safety and account-deletion requirements. Pin actual versions in the repository only after a clean build/test.

**Current next action:** Finish Step 1 phone verification and implement Step 2's financial model/fixtures, updating `progress_tracker.md` at each step. Continue through the authorized offline milestone as dependencies are verified. Keep pending manual checks visible and do not claim Gate A before its complete acceptance suite passes.
