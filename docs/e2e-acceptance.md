# Acceptance checks

The complete milestone matrix is [plan.md section 8](../plan.md#8-end-to-end-acceptance-matrix).
Actual results and environments belong in [verification-log.md](verification-log.md).

For each build, verify:

- A clean checkout builds debug/release and the instrumentation APK with JDK 17.
- Repository hygiene and Android lint pass. Report JVM tasks with no tests honestly.
- Install and launch on an API 26+ emulator/device; no crash or permission prompt.
- Reach all four tabs; selected destination survives activity recreation; back
  navigation returns appropriately without growing duplicate tab stacks.
- Light/dark theme, rotation, 200% font and TalkBack remain usable.
- Inspect the release merged manifest/APK and backup exclusions; no SMS,
  notification-listener, broad-storage or other sensitive collection capability.
- Debug is labeled FinanceGenie Dev and has a separate application ID/data sandbox.

Step 4 automated acceptance on the connected A063 is recorded in the verification log:

- [x] Account/category management, signed card positions and statement observations.
- [x] ₹249.50 → ₹199.50 edit, rotation, delete/undo, delete/restore and filters.
- [x] Six shared-fixture events through UI; exact salary/refund/transfer/card totals.
- [x] Invalid fields, linked-refund restrictions, archive references, source labels
  and rapid/stale-save protection.
- [x] Committed UI data survives a separately verified process restart.
- [x] Load error/retry; financial semantics hidden by the lock gate; live secure flags.
- [x] Compose-local 200% font/dark-mode interactions and normal-font light rendering.
- [x] Real Android credential enable/unlock/cancel and 30-second background timeout;
  user confirmed on 9 November 2026.
- [x] Screenshot/recent-app protection and actual TalkBack interaction; user
  confirmed on the A063 on 9 November 2026.

Step 4 is PASS with automated evidence and the user's manual phone confirmations.

Step 5 acceptance on the same phone:

- [x] Overall/category limits are independent; zero and no-budget states differ.
- [x] ₹5,000 limit and ₹4,000 spending show ₹1,000 remaining; another ₹1,500
  shows ₹500 over, with text warnings at the documented thresholds.
- [x] Later-month refunds, negative net spend, backdated edits and transfer exclusion
  agree between domain reports and the budget UI.
- [x] Next-month copy preserves source/existing target values, uses new IDs, skips
  archived categories and rolls back partial failure. December targets January.
- [x] Duplicate/stale saves and stale copy/removal are protected; explicit removal
  preserves financial records and other monthly limits.
- [x] Budget values survive rotation and a separately verified process restart.
- [x] A 200% font/dark budget layout is readable and its controls are reachable;
  navigation/transaction/privacy regression tests pass.

Step 5 is PASS. Budget-specific human TalkBack review belongs in the full-app
accessibility walkthrough at Gate A; it is not claimed by the automated layout test.

Step 6 correctness acceptance on the same phone:

- [x] Golden metrics agree across summary, signed categories, daily/monthly values
  and recent matching activity, including refunds and edit/delete convergence.
- [x] Account/category/literal search and month/all-date filters are shared with
  Transactions. Transfers match either account without becoming spending/income.
- [x] Full-month budgets and current estimated positions retain their explicit
  full-ledger scope when activity is filtered.
- [x] Empty/single/1,000-row cases, negative categories, long archived labels,
  200% dark layout and exact text alternatives pass.
- [x] Revision cache retains committed consistency across other repositories,
  nested rollbacks, dispatcher changes and a separate DB connection.
- [x] Final 20-launch/30-operation baseline recorded with artifact hashes and method.
- [ ] Startup confirmation ≤2s: observed 2.28s P95 including automation overhead.
  Standard AndroidX TTID/TTFD is still incomplete at 14/20 and remains open for Gate A.

Step 6's analytics/baseline gate is PASS. Performance target outcomes are separate
from completion of the baseline; see
[the measurements](performance.md) and [verification](verification-log.md).
Budget-save timing and human analytics-specific TalkBack review remain Gate A
checks. Do not mark Gate A complete: recovery, performance hardening,
full-app accessibility and actual OS backup behavior retain their later checks.
Recovery is required before using real personal finances.

Step 7 acceptance on the same phone:

- [x] Real system picker, strict bounded UTF-8/quoted fields, explicit mappings/
  dates, invalid-row reasons, exact counts and debit/credit totals.
- [x] Preview/cancel writes nothing; rotation retains an in-memory review draft.
- [x] Explicit bulk expense confirmation and individual income/refund/transfer
  decisions; linked refund caps and unlinked confirmation remain enforced.
- [x] Manual/CSV matching despite differing descriptions, both transfer legs,
  reference/overlap/reordered files and distinct legitimate identical rows.
- [x] Same-file replay across mappings/accounts is blocked; undone files offer
  explicit restore; accepted-subset file reservation is explained.
- [x] The 100-row corpus accepts 83 rows (81 new/two matches), with seven needing
  review and ten invalid; exact ₹765.50 spending/₹20 income are verified.
- [x] Atomic disk-failure rollback and a real process stop after an actual insert
  leave the prior committed ledger intact; fresh readers verify undo/replay/restore.
- [x] History preserves matched records, repeated undo/restore works, and edited
  rows/tombstones/new evidence/linked refunds reject conflicting batch operations.
- [x] 200% dark layout/refund controls, real picker content hash and all existing
  entry/privacy/budget/analytics regressions pass. Normal apps contain no fixtures,
  crash hooks or extra collection permission.
- [x] A 10,000-row import into 10,000 existing events reaches exact verified state
  in 5.25 seconds, below 15 seconds. This is one observation excluding provider/
  human review time; sampled memory does not establish a continuous peak.

Step 7 is PASS. Versioned full backup/restore, safe CSV report export and local
deletion are Step 8. Human CSV-specific TalkBack review joins the full-app Gate A
walkthrough; prior startup/trace, budget-save and OS backup checks remain open.
