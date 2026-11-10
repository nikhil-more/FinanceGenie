#!/usr/bin/env bash
set -u

if [[ "${CI:-}" != "true" ]] || [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Run this aggregate only on a disposable CI emulator." >&2
  exit 2
fi

# Each driver owns separate synthetic fixtures. Run all suites for diagnostics,
# and retain a failing exit status if any mandatory suite fails.
gate_status=0
python3 scripts/verify_acceptance_on_device.py --output /tmp/release-acceptance || gate_status=1
python3 scripts/verify_storage_on_device.py --ui || gate_status=1
python3 scripts/verify_csv_on_device.py --output /tmp/csv-acceptance || gate_status=1
python3 scripts/verify_recovery_on_device.py --output /tmp/recovery-acceptance || gate_status=1
exit "$gate_status"
