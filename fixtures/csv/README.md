# Synthetic CSV fixtures

These files contain invented records only. `generic-example.csv` demonstrates the
documented six-column format. `acceptance-100.csv` is shared by JVM and phone tests:

- 76 ordinary ₹10 debits, one ₹11 debit, one ₹12 debit and two identical ₹1.25
  debits: ₹785.50 total. The ₹11/₹12 records are the two manual-match fixtures.
- Ten ₹20 credits needing classification review: ₹200 total.
- Ten invalid records: ambiguous/missing dates, signed/zero/fractional amounts,
  future/before-opening dates, unsupported direction, excessive merchant length,
  and wrong column count.
- The first merchant includes a quoted comma, doubled quote and embedded newline.

Tests fix the clock at 30 November 2026 and open accounts on 1 November 2026.
See [the import contract](../../docs/csv-import.md) for counts with pre-existing
manual matches, accepted subsets and exact financial expectations. No fixture is
packaged as data in normal debug/release APKs; CSV assets are test-only.
