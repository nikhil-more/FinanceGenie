# Offline Overview and analytics

Overview and Transactions share account, category, merchant/notes search and
calendar-month/all-date selection. The Overview filter panel can be collapsed;
its scope and search summary stay visible. Selecting another calendar month
returns to month mode. Search is a literal, case-insensitive substring, capped at
200 characters. Transfers match either participating account but do not become
income or spending. Uncategorized includes expenses recorded without a category.

Only committed, non-deleted events enter activity totals, category bars, trends
and the five recent matching transactions. Showing tombstones in Transactions
never changes those totals. A linked refund can match a filter while its original
purchase does not; validation always sees the complete ledger before selection.

Overview displays gross expenses, received refunds, net spending, recorded income
and recorded surplus. Integer paise and checked exact arithmetic remain the source
for every label; floating-point fractions are used only to size pixels.

Two deliberate full-ledger views stay independent of activity filters:

- Current tracked positions include all accounts and recorded history through today,
  even when browsing an older/future activity month. The UI labels them estimated,
  gives the as-of date and explains their scope.
- Budget progress always uses all accounts and the complete selected calendar month.
  Filtering the transaction feed cannot make an exceeded monthly limit look safe.

## Charts and exact alternatives

Category bars extend left for net refunds and right for net expenses, with a
visible zero baseline and exact signed amounts beside every category. Archived
category history is retained and labeled. Long names wrap rather than disappearing.
Charts do not force negative spending into a pie or percentage allocation.

Daily bars include zero-activity days. In month mode they cover that calendar
month (including leap day); in all-date mode they show the last 30 days through
today. The heading gives exact dates. **Show daily values** reveals every date
and exact amount as readable text. Monthly rows show six calendar buckets ending
in the selected month, or in the current month for all-date selection, with zero
buckets and exact values. These bounded chart windows do not narrow all-date
summary totals or recent transactions.

Recent activity uses effective date, creation metadata and a stable ID tie-breaker.
It preserves original source labels, supports editing, and links to Transactions
with the same filters. Edits, deletions, refunds and undo recompute all affected
surfaces from one complete ledger revision; no expected recomposition count is used
as a correctness test.

## Implementation and evidence

`LedgerCalculator.reports` validates once per batch and reuses account positions
with the same as-of date. `AnalyticsWindow` bounds presentation series and
`buildAnalytics` creates zero-filled, signed data. Room schema v1 is unchanged.
The ViewModel prepares complete coherent snapshots on a background dispatcher;
the UI uses lazy lists and small native chart components, without a chart library.

JVM and phone tests cover the shared golden fixture, out-of-scope linked purchases,
literal search, account/category/date selection, budget independence, edit convergence,
empty/single/1,000-event cases and negative/long-label layouts. Actual outcomes and
limits belong to `verification-log.md` and `progress_tracker.md`. The separate
profileable performance setup is documented in `performance.md`.
