# Calendar-month budgets

The Budgets tab uses the same selected month as Overview and Transactions. Browse
past months or plan a future month. Recorded events still cannot be future-dated;
future budget limits do not predict spending or account balances.

Add one overall limit and/or one limit per category for each month. Overall and
category limits apply independently to the same ledger spending; category limits
are not allocations that must add up to the overall limit. Limits accept exact
INR values with up to two decimals and the existing 1,000,000,000,000 paise ceiling.

Each card shows the limit, expenses, refunds, net spending and remaining amount.
Negative remaining is shown as **Over by** with its positive magnitude and an
explicit **Limit exceeded** label. The numbers come from domain reports over the
complete committed ledger. Transfers, income and adjustments do not count as
budget spending. Deleted transactions are excluded, and corrections/undo update
the applicable month automatically.

Refunds reduce spending in their received month, including linked refunds for an
earlier purchase. A later month can therefore have negative net spending and
remaining above its limit. The earlier purchase month stays unchanged.

Warnings appear as text in the app: **Approaching limit** from 80%, **At limit**
at exactly 100%, and **Limit exceeded** above it. They use exact integer comparisons
and include text rather than relying only on color. No notification permission,
background reminder or rollover is introduced.

**A ₹0 limit is a budget.** It permits zero net spending and displays the at-limit
state when net spending is zero. **No budget** means the scope has no cap at all.
Use the confirmed **Remove budget** action to return to that state. Removal is a
revision-checked deletion of configuration; recorded financial events and other
months are preserved. Editing changes only the amount; month/scope are fixed so
an edit cannot move a historical limit into another month.

## Copy to next month

1. Select the source month and choose **Copy limits to next month**.
2. Review the destination month and counts of new limits, existing limits kept,
   and archived category limits skipped.
3. Confirm to create new records for eligible missing scopes, then view that month.

Source limits, transactions and existing destination limits are preserved. A copy
from December targets January of the following year. Archived category limits
stay visible in their original month but are not copied into a new month; existing
destination limits take precedence even if that category has since been archived.
There is no automatic carry-forward of spending or unused allowance.

The repository performs the copy in one Room transaction. A changed ledger revision
invalidates an open confirmation; reopen it to review current values. Failed
inserts roll back every copied row and the ledger revision. Repeating a copy cannot
overwrite target values or create duplicate month/scope records. A no-op copy does
not increment the ledger revision. Database schema v1 remains unchanged.

## Verification

Domain tests cover exact warning boundaries, ₹5,000/₹4,000/₹1,500 arithmetic,
independent scopes, zero/no-budget states, later refunds, backdated corrections,
copy conflicts, archives and December/January. Phone repository tests inject a
failure during the second copied insert and check rollback and stale/remove guards.
UI tests exercise the same journeys through the actual forms and read reports,
with separate test storage. The persistence probe now saves both an expense and
budget, stops only FinanceGenie Dev, and verifies exact values in a new process.

Use `scripts/verify_storage_on_device.py --ui` after building both APKs, or
`--budget-ui-only` to run the budget UI/probe independently. Outcomes, hardware,
tradeoffs and any unrun checks are recorded in the progress/verification documents.
