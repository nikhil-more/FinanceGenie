#!/usr/bin/env python3
"""Run storage and optional UI checks without clearing user app data."""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_PACKAGE = "com.financegenie.android.debug"
RUNNER = APP_PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PROBE = "com.financegenie.android.data.local.PersistenceRestartTest"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="Path to Android platform-tools adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="Select a phone when several are connected; never logged")
    parser.add_argument("--ui", action="store_true", help="Also verify manual-entry UI, privacy and a fresh-process UI read")
    parser.add_argument("--ui-only", action="store_true", help="Run the UI checks without repeating the storage suite")
    parser.add_argument("--budget-ui-only", action="store_true", help="Run budget UI checks and the UI persistence probe independently")
    parser.add_argument("--analytics-ui-only", action="store_true", help="Run analytics UI checks independently")
    args = parser.parse_args()
    apk_paths = [
        ROOT / "apps/android/build/outputs/apk/debug/android-debug.apk",
        ROOT / "apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk",
    ]
    if not all(path.is_file() for path in apk_paths):
        raise RuntimeError("Build assembleDebug and assembleDebugAndroidTest before running this check")

    devices = subprocess.run([args.adb, "devices"], capture_output=True, text=True, timeout=30)
    if devices.returncode:
        raise RuntimeError("ADB could not list devices; check platform-tools and local server access")
    ready = [parts[0] for line in devices.stdout.splitlines() if len(parts := line.split()) == 2 and parts[1] == "device"]
    if args.serial:
        if args.serial not in ready:
            raise RuntimeError("The selected test phone is not connected/authorized")
        serial = args.serial
    elif len(ready) == 1:
        serial = ready[0]
    else:
        raise RuntimeError("Connect/authorize one test phone, or select one with --serial")

    def adb(label: str, arguments: list[str]) -> str:
        print(label, flush=True)
        try:
            result = subprocess.run([args.adb, "-s", serial, *arguments], capture_output=True, text=True, timeout=600)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"{label}: device command timed out") from None
        output = result.stdout.replace(serial, "<test-device>")
        error = result.stderr.replace(serial, "<test-device>")
        if output:
            print(output, flush=True)
        if error:
            print(error, flush=True)
        if result.returncode:
            raise RuntimeError(f"{label}: device command failed")
        return output

    def instrument(label: str, arguments: list[str]) -> int:
        output = adb(label, ["shell", "am", "instrument", "-w", "-r", *arguments, RUNNER])
        match = re.search(r"\bOK\s*\((\d+) tests?\)", output)
        if not match or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
            raise RuntimeError(f"{label}: instrumentation did not pass")
        if "INSTRUMENTATION_STATUS_CODE: -3" in output or "INSTRUMENTATION_STATUS_CODE: -4" in output:
            raise RuntimeError(f"{label}: mandatory tests were skipped")
        count = int(match.group(1))
        if count < 1:
            raise RuntimeError(f"{label}: no test ran")
        return count

    for path in apk_paths:
        output = adb(f"Install {path.name}", ["install", "-r", "-t", str(path)])
        if "Success" not in output:
            raise RuntimeError("Test APK installation was not accepted")
    count = 0
    fresh_process_checks = 0
    if not args.ui_only and not args.budget_ui_only and not args.analytics_ui_only:
        count = instrument("Storage suite", ["-e", "package", "com.financegenie.android.data.local"])
        instrument("Seed process-restart fixture", ["-e", "class", PROBE + "#a_seedPersistentLedger"])
        adb("Stop only the development app", ["shell", "am", "force-stop", APP_PACKAGE])
        instrument("Verify fixture in a fresh process", [
            "-e", "class", PROBE + "#b_verifyPersistentLedgerAndCleanUp", "-e", "requireFreshProcess", "true",
        ])
        fresh_process_checks += 1
    ui_count = 0
    if (args.ui or args.ui_only) and not args.budget_ui_only and not args.analytics_ui_only:
        classes = ["NavigationTest", "ManagementUiTest", "LedgerUiTest", "PrivacyUiTest", "LayoutUiTest", "FailureUiTest"]
        ui_count = instrument("Manual-entry and privacy UI suite", [
            "-e", "class", ",".join("com.financegenie.android." + name for name in classes),
        ])
    if (args.ui or args.ui_only or args.budget_ui_only) and not args.analytics_ui_only:
        ui_count += instrument("Calendar-budget UI suite", [
            "-e", "class", "com.financegenie.android.BudgetUiTest,com.financegenie.android.BudgetHistoryUiTest",
        ])
        ui_probe = "com.financegenie.android.UiRestartTest"
        instrument("Save an expense and budget through the UI", ["-e", "class", ui_probe + "#a_saveExpenseThroughUi"])
        adb("Stop only the development app", ["shell", "am", "force-stop", APP_PACKAGE])
        instrument("Read the UI in a fresh process", [
            "-e", "class", ui_probe + "#b_readExpenseInFreshProcess", "-e", "requireFreshProcess", "true",
        ])
        fresh_process_checks += 1
    if args.ui or args.ui_only or args.analytics_ui_only:
        ui_count += instrument("Scoped analytics UI suite", [
            "-e", "class", "com.financegenie.android.AnalyticsUiTest,com.financegenie.android.AnalyticsScaleUiTest,com.financegenie.android.AnalyticsSingleUiTest",
        ])
    output = adb("Launch FinanceGenie", ["shell", "am", "start", "-W", "-n", APP_PACKAGE + "/com.financegenie.android.MainActivity"])
    if "Status: ok" not in output:
        raise RuntimeError("Development app launch did not succeed")
    print(f"PASS: {count} storage tests, {ui_count} UI tests, {fresh_process_checks} fresh-process checks, and app launch", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(f"Storage verification failed: {error}", file=sys.stderr)
        sys.exit(1)
