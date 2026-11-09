# Threat model

Scope: local Android ledger and manual-entry UI through Step 4. Financial records,
notes and privacy preferences are sensitive assets governed by [plan.md](../plan.md).

| Threat | Current control | Remaining verification/work |
|---|---|---|
| Secrets or personal records in source | Synthetic content, ignore private config/signing files, limited credential-pattern guard | Comprehensive secret review before release |
| Unintended collection/network access | No bank/cloud/analytics integrations or sensitive permissions | Inspect merged/packaged release permissions after build |
| OS backup or device-transfer exposure | `allowBackup=false` plus explicit legacy and modern exclusions | Test actual supported-device behavior |
| Shared or lost unlocked device | Optional system device-credential app lock; cold-start gate and configurable background timeout | User confirmed device credential, cancellation and timeout on A063; no database-encryption claim |
| Screenshots and recent-app previews | Default `FLAG_SECURE`, inherited by dialogs; immediate lock shield while backgrounded and recents restriction on API 33+ | User confirmed screenshot/preview protection on A063; recheck other supported devices; foreground sharing can be explicitly enabled |
| Crash/partial local financial writes | Room atomic groups, revision checks, persistent soft deletion and undo; phone storage/restart tests | Tested portable backup/restore in Step 8 |
| Financial content exposed by a locked screen | Remove the financial composition while gated; unlock grants remain in memory only | Keep lifecycle/semantics tests and physical system-auth checks |
| Malicious import/notification text | No ingestion capability in the scaffold | Bounded parsing, no executable markup/raw message storage later |
| Cross-owner cloud exposure or deletion resurrection | No cloud capability | Protocol/rules/deletion feasibility gate in C |

Trust boundaries are the Android app sandbox, user-selected document providers,
OS notification access, future authenticated Firebase user paths and any trusted
deletion service. Add each only in its authorized phase. A rooted/compromised OS
is outside the promised protection. App-private storage/UI lock does not mean
application-level database encryption. Never use real financial data for fixture,
log, screenshot or build verification.
