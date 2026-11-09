# Acceptance checks

The complete milestone matrix is [plan.md section 8](../plan.md#8-end-to-end-acceptance-matrix).
Actual results and environments belong in [verification-log.md](verification-log.md).

For the current foundation, verify:

- A clean checkout builds debug/release and the instrumentation APK with JDK 17.
- Repository hygiene and Android lint pass. Report JVM tasks with no tests honestly.
- Install and launch on an API 26+ emulator/device; no crash or permission prompt.
- Reach all four tabs; selected destination survives activity recreation; back
  navigation returns appropriately without growing duplicate tab stacks.
- Light/dark theme, rotation, 200% font and TalkBack remain usable.
- Inspect the release merged manifest/APK and backup exclusions; no SMS,
  notification-listener, broad-storage or other sensitive collection capability.
- Debug is labeled FinanceGenie Dev and has a separate application ID/data sandbox.

Build failure or a missing device leaves the relevant Step 1 check unrun/blocked.
Do not mark Gate A complete: the ledger, budgets, imports and recovery are still
future steps, and no real personal financial records should be entered yet.
