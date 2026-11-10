#!/usr/bin/env python3
"""Test personal recovery with synthetic profiles and a disposable recovery-check app."""

import argparse
import csv
import io
import json
import os
import queue
import re
import subprocess
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEV = "com.financegenie.android.debug"
CHECK = "com.financegenie.android.recoverycheck"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path, default=Path("/tmp/financegenie-step8-recovery-phone"))
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--storage-only", action="store_true")
    modes.add_argument("--ui-only", action="store_true")
    modes.add_argument("--drill-only", action="store_true")
    args = parser.parse_args()
    listed = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True, timeout=30)
    ready = [p[0] for line in listed.stdout.splitlines() if len(p := line.split()) == 2 and p[1] == "device"]
    serial = args.serial or (ready[0] if len(ready) == 1 else None)
    if serial not in ready:
        raise RuntimeError("Connect one authorized phone or select it with --serial")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(label: str, arguments: list[str], binary: bool = False, data: bytes | None = None):
        print(label, flush=True)
        result = subprocess.run(["adb", "-s", serial, *arguments], capture_output=True, input=data, timeout=300)
        if result.returncode:
            raise RuntimeError(label + " failed: " + result.stderr.decode(errors="replace").replace(serial, "<test-device>")[-3000:])
        return result.stdout if binary else result.stdout.decode(errors="replace").replace(serial, "<test-device>")

    def install(package: str):
        variant = "debug" if package == DEV else "recoveryCheck"
        for apk in (ROOT / f"apps/android/build/outputs/apk/{variant}/android-{variant}.apk",
                    ROOT / f"apps/android/build/outputs/apk/androidTest/{variant}/android-{variant}-androidTest.apk"):
            if not apk.is_file():
                raise RuntimeError("Build the required app/test APKs first: " + str(apk.relative_to(ROOT)))
            if "Success" not in adb("Install " + apk.name, ["install", "-r", "-t", str(apk)]):
                raise RuntimeError("APK installation failed")

    def instrument(package: str, label: str, klass: str, extra: list[str], filename: str):
        output = adb(label, ["shell", "am", "instrument", "-w", "-r", "-e", "class", klass, *extra, package + ".test/" + RUNNER])
        (args.output / filename).write_text(output)
        if (not re.search(r"OK \([1-9][0-9]* tests?\)", output) or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output
                or "INSTRUMENTATION_STATUS_CODE: -3" in output or "INSTRUMENTATION_STATUS_CODE: -4" in output):
            raise RuntimeError(label + " did not pass: " + output[-7000:])
        print(output[output.rfind("Time:"):].strip(), flush=True)

    base = "FinanceGenie-step8-synthetic-" + uuid.uuid4().hex[:12]
    try:
        if not args.drill_only:
            install(DEV)
        if not args.ui_only and not args.drill_only:
            instrument(DEV, "Recovery storage safety", "com.financegenie.android.recovery.RecoveryRepositoryTest", [], "storage.log")
        if not args.storage_only and not args.drill_only:
            classes = ",".join("com.financegenie.android." + name for name in ("RecoveryUiTest", "RecoveryPickerUiTest", "RecoveryFontUiTest"))
            instrument(DEV, "Recovery UI and real save/open pickers", classes, ["-e", "recoveryPickerBaseName", base], "ui.log")
            raw = adb("Read only the picker-created synthetic JSON", ["exec-out", "cat", f"/sdcard/Download/{base}.json"], binary=True)
            parsed = json.loads(raw)
            assert parsed["counts"]["transactions"] == "1000"
            raw_csv = adb("Read only the picker-created synthetic CSV", ["exec-out", "cat", f"/sdcard/Download/{base}.csv"], binary=True)
            rows = list(csv.DictReader(io.StringIO(raw_csv.decode("utf-8"))))
            assert len(rows) == 999
            assert any(row["notes"].startswith("'=SUM(A1)") for row in rows)
            (args.output / "picker-result.json").write_text(json.dumps({"jsonTransactions": 1000, "csvActiveRows": 999, "formulaTextEscaped": True}, indent=2) + "\n")
        if not args.storage_only and not args.ui_only:
            # Only this separate, synthetic package is ever uninstalled/cleared.
            def uninstall_check():
                for package in (CHECK + ".test", CHECK):
                    if f"package:{package}" in adb("Check synthetic package", ["shell", "pm", "list", "packages", package]).splitlines():
                        assert "Success" in adb("Uninstall only " + package, ["uninstall", package])
            uninstall_check(); install(CHECK)
            drill = "com.financegenie.android.recovery.RecoveryDrill"
            instrument(CHECK, "Seed and export 1,000 synthetic records", drill, ["-e", "recoveryDrillPhase", "seed"], "fresh-seed.log")
            backup = adb("Retain only the synthetic recovery backup", ["exec-out", "run-as", CHECK, "cat", "files/recovery-drill-only.json"], binary=True)
            uninstall_check(); install(CHECK)
            adb("Create only the fresh check app's private test-file directory", ["shell", "run-as", CHECK, "mkdir", "-p", "files"])
            # Shell v2 forwards stdin EOF; raw exec-out keeps a stdin reader open.
            adb("Supply the same backup to the freshly installed check app", ["shell", "-T", "run-as", CHECK, "sh", "-c", "'cat > files/recovery-drill-only.json'"], binary=True, data=backup)
            instrument(CHECK, "Assert fresh install is empty, then restore", drill, ["-e", "recoveryDrillPhase", "restore"], "fresh-restore.log")
            adb("Stop only the recovery-check app", ["shell", "am", "force-stop", CHECK])
            instrument(CHECK, "Verify durable recovery, replay and local deletion", drill, ["-e", "recoveryDrillPhase", "verify"], "fresh-verify.log")

            probe = "com.financegenie.android.recovery.RecoveryCrashProbe"
            for boundary in ("stage", "publish", "delete"):
                print("Wait for actual recovery boundary: " + boundary, flush=True)
                process = subprocess.Popen(["adb", "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", probe,
                                            "-e", "recoveryCrashPhase", "write", "-e", "recoveryBoundary", boundary, CHECK + ".test/" + RUNNER],
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                lines: queue.Queue = queue.Queue()
                def collect():
                    for line in process.stdout:
                        lines.put(line.replace(serial, "<test-device>"))
                    lines.put(None)
                reader = threading.Thread(target=collect, daemon=True); reader.start()
                captured = []
                deadline = time.monotonic() + 90
                reached = False
                while time.monotonic() < deadline:
                    line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
                    if line is None:
                        break
                    captured.append(line)
                    if "INSTRUMENTATION_STATUS: recovery_boundary_reached=true" in line:
                        reached = True; break
                if not reached:
                    process.terminate()
                    raise RuntimeError("Recovery boundary was not reached: " + "".join(captured)[-5000:])
                adb("Stop only the synthetic check app at " + boundary, ["shell", "am", "force-stop", CHECK])
                process.wait(timeout=30); reader.join(timeout=5)
                (args.output / f"crash-{boundary}-writer.log").write_text("".join(captured))
                instrument(CHECK, "Fresh reader after " + boundary, probe,
                           ["-e", "recoveryCrashPhase", "read", "-e", "recoveryBoundary", boundary], f"crash-{boundary}-reader.log")
    finally:
        if not args.storage_only and not args.drill_only:
            for extension in ("json", "csv"):
                adb("Remove only the picker-owned synthetic export", ["shell", "rm", "-f", f"/sdcard/Download/{base}.{extension}"])
        output = adb("Reopen FinanceGenie Dev", ["shell", "am", "start", "-W", "-n", DEV + "/com.financegenie.android.MainActivity"])
        assert "Status: ok" in output
    print("PASS: selected recovery checks; the user's development ledger was never cleared", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, AssertionError, OSError, subprocess.SubprocessError, queue.Empty) as error:
        raise SystemExit("Recovery verification failed: " + str(error))
