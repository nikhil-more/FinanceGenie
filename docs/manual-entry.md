# Manual entry and privacy

Open **Settings → Accounts** to create a bank, cash or credit-card account. The
opening date must be no later than its recorded events. Use a negative position
for debt/overdraft; credit-card screens display debt as **Amount owed** and an
overpayment as **Credit / overpayment**. Account balances include archived accounts
and are always calculated through today, independent of the selected report month.

**Record statement balance** saves an end-of-day, user-reported observation and
its difference from the estimate. It never silently changes the ledger or makes
a balance bank-verified. Use a separate adjustment with a reason for a correction.

Manage categories under **Settings → Categories**. Archiving preserves IDs and
history while removing the account/category from new selection. Existing events
can retain an archived reference when corrected. Uncategorized cannot be archived.

Use **Add transaction** for all five event types. Amounts are INR with up to two
decimal places; dates use YYYY-MM-DD or the calendar picker. Credit-card bill
payments are transfers from the paying account to the card. Refunds can link to a
purchase, with the combined linked amount limited to that expense, or require
explicit confirmation when no purchase is recorded. Transfers and adjustments
are excluded from income/spending. Refunds affect their received month.

The Transactions tab offers month/all-date, account, category, merchant/notes and
deleted-record filters. Corrections retain their source labels. Delete keeps a
tombstone; the confirmation offers an atomic purchase-plus-refunds deletion when
needed. The snackbar offers revision-checked **Undo**. **Show deleted** also lets
you restore a record later; restore a purchase before its separately deleted refund.
Dismiss a transient snackbar when it covers a row action.

Draft IDs, values and original revisions survive rotation in a ViewModel.
Committed records survive process death; an unsubmitted form is not persisted.
If another operation changes an edited record, close and reopen the form rather
than overwriting that newer revision. Repeated save taps cannot enqueue duplicates.

## Privacy controls

**Protect screenshots and previews** starts enabled. Android prevents screenshots
and recording of the protected windows; form/selection dialogs inherit this policy.
It can be explicitly disabled for foreground screen sharing. While app lock is
enabled, the app still hides financial content when backgrounded and suppresses
recent-app snapshots on API 33+. Device/OEM behavior needs the phone check below.

**App lock** is optional and requires an Android device screen lock. Enabling it
opens Android's device-credential confirmation; FinanceGenie never receives or
stores the PIN, pattern or password. Cold starts require authentication. Returning
from the background requires authentication after the selected timeout (default
30 seconds; also immediately, one minute or five minutes). Financial content is
hidden immediately while backgrounded, including during the grace period.
Rotation retains an authenticated session; process death does not. Privacy settings
are committed before the UI reports success. This UI lock does not encrypt SQLite.

## Manual phone check

Use only synthetic amounts/names. Automated tests use separate in-memory/test-named
databases and never clear the app ledger or global phone settings.

1. Create a synthetic account/category and record ₹249.50, edit to ₹199.50,
   delete/undo and reopen the app. Check labels and keyboard access.
2. In Settings, enable App lock and authenticate directly in Android. Never share
   your credential. Use **Lock now**, cancel authentication and confirm the ledger
   stays hidden, then unlock. Leave longer than the timeout and return.
3. With protection enabled, check the recent-app preview and screenshot behavior.
   If you enable foreground sharing temporarily, turn protection back on afterward.
4. With TalkBack enabled by you, navigate the four tabs, account/category choices,
   transaction form, confirmation checkbox and validation message. Confirm useful
   spoken labels, focus order and reachable Save/Cancel. Turn it off when finished
   if it is not your normal setting. Automated 200% font/theme checks complement
   this check; they cannot prove the experience with TalkBack or OEM previews.

Record the actual results in `progress_tracker.md` and `docs/verification-log.md`.
An unrun manual gate remains pending.
