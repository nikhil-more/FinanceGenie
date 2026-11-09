# Initial threat model

Scope: Step 1 project foundation. Future financial data, notes, account hints,
imports and authentication are sensitive assets governed by [plan.md](../plan.md).

| Threat | Foundation control | Remaining verification/work |
|---|---|---|
| Secrets or personal records in source | Synthetic content, ignore private config/signing files, limited credential-pattern guard | Comprehensive secret review before release |
| Unintended collection/network access | No bank/cloud/analytics integrations or sensitive permissions | Inspect merged/packaged release permissions after build |
| OS backup or device-transfer exposure | `allowBackup=false` plus explicit legacy and modern exclusions | Test actual supported-device behavior |
| Shared or lost unlocked device | No personal records collected by the scaffold | System-authenticated app lock in Step 4; no encryption claim |
| Crash/partial local financial writes | No ledger persistence yet | Room atomic writes in Step 3; tested backup/restore in Step 8 |
| Malicious import/notification text | No ingestion capability in the scaffold | Bounded parsing, no executable markup/raw message storage later |
| Cross-owner cloud exposure or deletion resurrection | No cloud capability | Protocol/rules/deletion feasibility gate in C |

Trust boundaries are the Android app sandbox, user-selected document providers,
OS notification access, future authenticated Firebase user paths and any trusted
deletion service. Add each only in its authorized phase. A rooted/compromised OS
is outside the promised protection. App-private storage/UI lock does not mean
application-level database encryption. Never use real financial data for fixture,
log, screenshot or build verification.
