#!/usr/bin/env python3
"""Verify synthetic CSV UI/picker and real interrupted-transaction recovery only."""

import argparse
import os
import queue
import re
import subprocess
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = "com.financegenie.android.debug"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
PROBE = "com.financegenie.android.CsvCrashProbe"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path, default=Path("/tmp/financegenie-step7-csv-phone"))
    phases = parser.add_mutually_exclusive_group()
    phases.add_argument("--crash-only", action="store_true")
    phases.add_argument("--ui-only", action="store_true")
    phases.add_argument("--picker-only", action="store_true")
    args = parser.parse_args()
    listed = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True, timeout=30)
    ready = [p[0] for line in listed.stdout.splitlines() if len(p := line.split()) == 2 and p[1] == "device"]
    serial = args.serial or (ready[0] if len(ready) == 1 else None)
    if serial not in ready:
        raise RuntimeError("Connect one authorized phone or select it with --serial")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(label: str, arguments: list[str], timeout: int = 300) -> str:
        print(label, flush=True)
        result = subprocess.run(["adb", "-s", serial, *arguments], capture_output=True, text=True, timeout=timeout)
        output = (result.stdout + result.stderr).replace(serial, "<test-device>")
        if result.returncode:
            raise RuntimeError(label + " failed: " + output[-5000:])
        return output

    def instrument(label: str, arguments: list[str], file_name: str) -> str:
        output = adb(label, ["shell", "am", "instrument", "-w", "-r", *arguments, RUNNER])
        (args.output / file_name).write_text(output)
        if not re.search(r"OK \([1-9][0-9]* tests?\)", output) or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
            raise RuntimeError(label + " did not pass: " + output[-8000:])
        if "INSTRUMENTATION_STATUS_CODE: -3" in output or "INSTRUMENTATION_STATUS_CODE: -4" in output:
            raise RuntimeError(label + " skipped a required case")
        print(output[output.rfind("Time:"):].strip(), flush=True)
        return output

    for path in [ROOT / "apps/android/build/outputs/apk/debug/android-debug.apk",
                 ROOT / "apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk"]:
        if not path.is_file() or "Success" not in adb("Install " + path.name, ["install", "-r", "-t", str(path)]):
            raise RuntimeError("Build both development/test APKs first")
    remote = "/sdcard/Download/FinanceGenie-step7-synthetic-" + uuid.uuid4().hex[:12] + ".csv"
    try:
        if not args.crash_only:
            adb("Prepare only the unique synthetic picker file", ["push", str(ROOT / "fixtures/csv/acceptance-100.csv"), remote])
            adb("Index only that synthetic file for DocumentsUI", ["shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + remote])
            classes = ["CsvPickerUiTest"] if args.picker_only else ["CsvUiTest", "CsvMatchUiTest", "CsvFailureUiTest", "CsvPickerUiTest", "CsvFontUiTest"]
            instrument("CSV UI and real document picker", ["-e", "class", ",".join("com.financegenie.android." + name for name in classes),
                       "-e", "csvPickerFileName", remote.rsplit("/", 1)[1]], "ui.log")
        if not args.ui_only and not args.picker_only:
            print("Wait for an actual first insert inside the pending SQLite transaction", flush=True)
            process = subprocess.Popen(["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", PROBE,
                                        "-e", "csvCrashPhase", "write", RUNNER], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            lines: queue.Queue = queue.Queue()
            def collect() -> None:
                for line in process.stdout:
                    lines.put(line.replace(serial, "<test-device>"))
                lines.put(None)
            reader = threading.Thread(target=collect, daemon=True)
            reader.start()
            captured = []
            deadline = time.monotonic() + 90
            reached = False
            while time.monotonic() < deadline:
                line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
                if line is None:
                    break
                captured.append(line)
                if "INSTRUMENTATION_STATUS: csv_inside_commit=true" in line:
                    reached = True
                    break
            if not reached:
                process.terminate()
                raise RuntimeError("Commit gate was not reached: " + "".join(captured)[-7000:])
            adb("Stop only FinanceGenie Dev while its synthetic transaction is open", ["shell", "am", "force-stop", APP])
            process.wait(timeout=30)
            reader.join(timeout=5)
            while not lines.empty():
                line = lines.get_nowait()
                if line is not None:
                    captured.append(line)
            (args.output / "interrupted-writer.log").write_text("".join(captured))
            instrument("Verify rollback and the prior committed batch in a fresh process", ["-e", "class", PROBE, "-e", "csvCrashPhase", "verify"], "recovery.log")
            adb("Stop only FinanceGenie Dev after durable batch undo", ["shell", "am", "force-stop", APP])
            instrument("Verify durable undone replay protection and explicit restore", ["-e", "class", PROBE, "-e", "csvCrashPhase", "restore"], "restore.log")
    finally:
        if not args.crash_only:
            adb("Remove only the driver-owned synthetic CSV", ["shell", "rm", "-f", remote])
        output = adb("Reopen FinanceGenie Dev", ["shell", "am", "start", "-W", "-n", APP + "/com.financegenie.android.MainActivity"])
        if "Status: ok" not in output:
            raise RuntimeError("App launch did not succeed")
    print("PASS: selected CSV UI/picker and/or interrupted-commit recovery checks; no user ledger was cleared", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.SubprocessError, queue.Empty) as error:
        raise SystemExit("CSV verification failed: " + str(error))
