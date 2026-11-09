# Shared ledger fixtures

All records here are synthetic. Amounts are base-10 integer strings in INR paise,
including expected results. Expected values are entered independently of the
production calculation engine. `schemaVersion` versions this fixture contract,
not the future portable-backup format or Room schema.

`november-baseline.json` encodes the salary, groceries/refund, cash transfer and
card purchase/payment journey in plan section 2. The JVM suite reads this exact
file through the `financegenie.fixtures` test property; future TypeScript clients
must read the same fixture, not maintain a separate copy of expected totals.

Account/transaction/budget IDs are stable synthetic UUIDs. Category IDs are stable
strings, including the reserved `uncategorized` ID. Date-only records have no
invented event instant. Additional business cases are unit tests alongside this
baseline; add shared files before introducing another client that needs them.
