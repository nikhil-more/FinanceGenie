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
- [ ] Real Android credential enable/unlock/cancel and background timeout.
- [ ] OEM screenshot/recent-app behavior and actual TalkBack interaction.

Unrun mandatory checks remain pending. Step 4 is IN PROGRESS until the user's
manual privacy/accessibility results are confirmed. Do not mark Gate A complete:
budgets, charts/performance, imports, recovery and actual OS backup behavior still
have their later gates. Recovery is required before using real personal finances.
