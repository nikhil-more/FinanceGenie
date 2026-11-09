# Product requirements

Recorded 9 November 2026. [plan.md](../plan.md) is the canonical financial contract
and acceptance specification; this document records the scope being started.

The current request authorizes the basic Android project foundation (Step 1),
with the minimum Step 0 decisions needed to create it. Later features remain
unimplemented. The app starts without sign-in, payment credentials or sensitive
permissions. Initial currency is INR, reporting zone Asia/Kolkata, minimum Android
API 26. The individual test phone is not yet identified.

The foundation offers Overview, Transactions, Budgets and Settings navigation,
light/dark themes, accessible text resources and empty states. It contains no
invented financial values or nonfunctional add/import buttons. Debug and release
application IDs differ to isolate their data.

| Journey | Implementation destination |
|---|---|
| Manual expense/income and restart | Steps 2–4 |
| Transfers, refunds, card purchase/payment | Financial contract in plan section 2; Steps 2–4 |
| Calendar-month and category budgets | Step 5; refunds count in the month received |
| Reports and CSV import | Steps 6–7 |
| Export, restore and local deletion | Step 8; required before real personal data |
| Optional notification detection | B, Steps 10–14 |
| Optional identity/sync and private web | C–D, Steps 15–20 |

The six plan journeys were traced to the steps above. The golden synthetic ledger
has Bank ₹55,501, Cash ₹1,000, Card ₹0 and net spending ₹3,499. The financial
contract defines positive transaction amounts, liability signs, atomic transfers,
refund limits, exact paise arithmetic and reporting dates before their code exists.

Bank access, payment initiation, multi-currency conversion, custom periods,
rollover, recurrence, AI and SMS are deferred as specified in the plan. The scaffold
does not introduce credentials, cloud services or payment instructions.
