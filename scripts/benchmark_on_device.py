#!/usr/bin/env python3
"""Measure only the separate synthetic .benchmark app; never clear the development ledger."""

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = "com.financegenie.android.benchmark"
DRIVER = "com.financegenie.benchmarks"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"


def stats(values: list[float]) -> dict:
    ordered = sorted(float(value) for value in values)
    if not ordered or not all(math.isfinite(value) for value in ordered):
        raise RuntimeError("Benchmark has no finite timing samples")
    return {"runs": values, "p50": ordered[math.ceil(len(ordered) * 0.5) - 1],
            "p95": ordered[math.ceil(len(ordered) * 0.95) - 1], "max": ordered[-1]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="Select privately when multiple devices are connected")
    parser.add_argument("--output", type=Path, default=Path("/tmp/financegenie-step6-performance"))
    phases = parser.add_mutually_exclusive_group()
    phases.add_argument("--startup-only", action="store_true", help="Measure startup without repeating in-process operations")
    phases.add_argument("--operations-only", action="store_true", help="Measure operations against an already prepared 10,000-event fixture")
    phases.add_argument("--csv-only", action="store_true", help="Measure one verified 10,000-row CSV import into the isolated 10,000-event fixture")
    args = parser.parse_args()
    apks = [ROOT / "apps/android/build/outputs/apk/benchmark/android-benchmark.apk",
            ROOT / "benchmarks/build/outputs/apk/benchmark/benchmarks-benchmark.apk",
            ROOT / "apps/android/build/outputs/apk/androidTest/benchmark/android-benchmark-androidTest.apk"]
    if not all(path.is_file() for path in apks):
        raise RuntimeError("Build the benchmark app/driver and benchmark AndroidTest APK before measuring")
    artifact_hashes = [hashlib.sha256(path.read_bytes()).hexdigest() for path in apks]
    app_bytes = apks[0].stat().st_size
    listed = subprocess.run([args.adb, "devices"], text=True, capture_output=True, check=True, timeout=30)
    ready = [parts[0] for line in listed.stdout.splitlines() if len(parts := line.split()) == 2 and parts[1] == "device"]
    serial = args.serial or (ready[0] if len(ready) == 1 else None)
    if serial not in ready:
        raise RuntimeError("Connect/authorize one phone, or select it with --serial")
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "summary.json").exists():
        raise RuntimeError("Output already contains a completed report; choose a fresh --output directory")

    def adb(label: str, arguments: list[str], timeout: int = 60) -> str:
        print(label, flush=True)
        result = subprocess.run([args.adb, "-s", serial, *arguments], text=True, capture_output=True, timeout=timeout)
        output = (result.stdout + result.stderr).replace(serial, "<test-device>")
        if result.returncode:
            raise RuntimeError(label + " failed: " + output[-4000:])
        return output

    def instrument(label: str, runner: str, arguments: list[str], log_name: str) -> str:
        output = adb(label, ["shell", "am", "instrument", "-w", "-r", *arguments, runner], timeout=1200)
        (args.output / log_name).write_text(output)
        if not re.search(r"\bOK\s*\(1 test\)", output) or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
            raise RuntimeError(label + " did not pass: " + output[-7000:])
        if "INSTRUMENTATION_STATUS_CODE: -3" in output or "INSTRUMENTATION_STATUS_CODE: -4" in output:
            raise RuntimeError(label + " was skipped")
        return output

    for apk in apks:
        if "Success" not in adb("Install " + apk.name, ["install", "-r", "-t", str(apk)]):
            raise RuntimeError("Benchmark APK installation failed")
    if args.csv_only:
        instrument("Prepare only the isolated synthetic benchmark fixture", DRIVER + "/" + RUNNER,
                   ["-e", "class", DRIVER + ".CsvFixtureSetup"], "csv-fixture.log")
        output = instrument("Measure 10,000-row CSV preview/classification/commit and verify exact totals", TARGET + ".test/" + RUNNER,
                            ["-e", "class", "com.financegenie.android.CsvImportPerformanceTest"], "csv-import.log")
        match = re.search(r"^INSTRUMENTATION_STATUS: financegenie_csv_performance=(.+)$", output, re.MULTILINE)
        if not match:
            raise RuntimeError("CSV performance result is missing")
        measured = json.loads(match.group(1))
        if (measured.get("existingRecords"), measured.get("importedRows"), measured.get("recordsAfter")) != (10_000, 10_000, 20_000):
            raise RuntimeError("CSV benchmark must verify all imported rows and the final ledger size")
        elapsed = float(measured["totalToVerifiedMs"])
        if not math.isfinite(elapsed) or elapsed < 0:
            raise RuntimeError("CSV benchmark duration must be finite and nonnegative")
        if artifact_hashes != [hashlib.sha256(path.read_bytes()).hexdigest() for path in apks]:
            raise RuntimeError("Benchmark APKs changed during measurement")
        report = {
            "deviceModel": adb("Read test model", ["shell", "getprop", "ro.product.model"]).strip(),
            "apiLevel": int(adb("Read API level", ["shell", "getprop", "ro.build.version.sdk"]).strip()),
            "buildType": "benchmark, non-debuggable, profileable",
            "compilation": "installed state; warm filesystem cache",
            "method": "One 10,000-row CSV import into 10,000 existing records; parse, preview, classification, atomic commit and exact verification; human review time excluded",
            "appApkBytes": app_bytes, "appApkSha256": artifact_hashes[0],
            "driverApkSha256": artifact_hashes[1], "testApkSha256": artifact_hashes[2],
            "csvImport": measured, "targetsMet": {"csvImport10000AtMost15000ms": elapsed <= 15_000},
        }
        path = args.output / "summary.json"
        path.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"csvImportToVerifiedMs": elapsed, "targetsMet": report["targetsMet"]}), flush=True)
        print("Saved aggregate timing report: " + str(path), flush=True)
        return 0
    startup = {}
    if not args.operations_only:
        output = instrument("Measure 20 cold launches through committed and confirmed Overview", DRIVER + "/" + RUNNER, [
            "-e", "class", DRIVER + ".OfflineReadinessBenchmark",
        ], "startup.log")
        match = re.search(r"^INSTRUMENTATION_STATUS: financegenie_startup=(.+)$", output, re.MULTILINE)
        if not match:
            raise RuntimeError("Startup readiness result is missing")
        measured = json.loads(match.group(1))
        if measured.get("records") != 10_000 or measured.get("operations") != 20 or measured.get("processColdVerified") is not True:
            raise RuntimeError("Startup must verify 20 process-cold launches of the 10,000-event fixture")
        for metric in ("launchToConfirmedOverviewMs", "launchToCommittedFrameMs"):
            runs = measured[metric]["runs"]
            if len(runs) != 20:
                raise RuntimeError("Startup measurement must contain exactly 20 runs")
            startup[metric] = stats(runs)
        startup["uiAutomatorIdleTimeoutMs"] = measured["uiAutomatorIdleTimeoutMs"]
    operations = {}
    if not args.startup_only:
        output = instrument("Measure 30 summaries, searches and saves", TARGET + ".test/" + RUNNER, [
            "-e", "class", "com.financegenie.android.OfflinePerformanceTest",
        ], "queries-and-saves.log")
        match = re.search(r"^INSTRUMENTATION_STATUS: financegenie_performance=(.+)$", output, re.MULTILINE)
        if not match:
            raise RuntimeError("In-process performance result is missing")
        operations = json.loads(match.group(1))
        if (operations.get("records"), operations.get("operations"), operations.get("budgetOperations")) != (10_000, 30, 30):
            raise RuntimeError("Operations must verify 30 expense and 30 budget saves on the 10,000-event fixture")
        for metric in ("snapshotReadMs", "summaryIncludingReadMs", "pagedSearchMs", "saveToVisibleMs", "budgetSaveToVisibleMs"):
            if len(operations[metric]["runs"]) != 30:
                raise RuntimeError("Every operation requires exactly 30 timing samples")
            operations[metric] = stats(operations[metric]["runs"])
    if artifact_hashes != [hashlib.sha256(path.read_bytes()).hexdigest() for path in apks]:
        raise RuntimeError("Benchmark APKs changed during measurement; rebuild before starting a new run")
    report = {
        "deviceModel": adb("Read test model", ["shell", "getprop", "ro.product.model"]).strip(),
        "apiLevel": int(adb("Read API level", ["shell", "getprop", "ro.build.version.sdk"]).strip()),
        "recordCount": 10_000, "buildType": "benchmark, non-debuggable, profileable",
        "compilation": "installed state; warm filesystem cache",
        "startupMethod": "process-cold launch request to committed frame and UI-confirmed Overview; includes broadcast and UIAutomator verification",
        "appApkBytes": app_bytes,
        "appApkSha256": artifact_hashes[0],
        "driverApkSha256": artifact_hashes[1],
        "testApkSha256": artifact_hashes[2],
        "startup": startup, "operations": operations,
        "targetsMet": {
            "coldUsableOverviewP95AtMost2000ms": startup["launchToConfirmedOverviewMs"]["p95"] <= 2000 if startup else None,
            "summaryP95AtMost300ms": operations["summaryIncludingReadMs"]["p95"] <= 300 if operations else None,
            "pagedSearchP95AtMost500ms": operations["pagedSearchMs"]["p95"] <= 500 if operations else None,
            "saveToVisibleP95AtMost300ms": operations["saveToVisibleMs"]["p95"] <= 300 if operations else None,
            "budgetSaveToVisibleP95AtMost300ms": operations["budgetSaveToVisibleMs"]["p95"] <= 300 if operations else None,
        },
    }
    path = args.output / "summary.json"
    path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"startupP95Ms": startup["launchToConfirmedOverviewMs"]["p95"] if startup else None,
                      "summaryP95Ms": operations["summaryIncludingReadMs"]["p95"] if operations else None,
                      "searchP95Ms": operations["pagedSearchMs"]["p95"] if operations else None,
                      "saveP95Ms": operations["saveToVisibleMs"]["p95"] if operations else None, "targetsMet": report["targetsMet"]}), flush=True)
    print("Saved aggregate timing report: " + str(path), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("Benchmark verification failed: " + str(error), file=sys.stderr)
        sys.exit(1)
