#!/usr/bin/env python3
"""Exercise a disposable non-debuggable candidate and actual framework backup filtering."""

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CANDIDATE = "com.financegenie.android.acceptance"
CONTROL = "com.financegenie.android.backupcontrol"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path, default=Path("/tmp/financegenie-step9-acceptance"))
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--smoke-only", action="store_true")
    modes.add_argument("--backup-only", action="store_true")
    args = parser.parse_args()
    ready = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True, timeout=30)
    devices = [p[0] for line in ready.stdout.splitlines() if len(p := line.split()) == 2 and p[1] == "device"]
    serial = args.serial or (devices[0] if len(devices) == 1 else None)
    if serial not in devices:
        raise RuntimeError("Connect one authorized test device or select it with --serial")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(label: str, arguments: list[str], timeout: int = 600) -> str:
        print(label, flush=True)
        result = subprocess.run(["adb", "-s", serial, *arguments], capture_output=True, text=True, timeout=timeout)
        output = (result.stdout + result.stderr).replace(serial, "<test-device>")
        if result.returncode:
            raise RuntimeError(label + " failed: " + output[-5000:])
        return output

    def install(variant: str):
        for path in (ROOT / f"apps/android/build/outputs/apk/{variant}/android-{variant}.apk",
                     ROOT / f"apps/android/build/outputs/apk/androidTest/{variant}/android-{variant}-androidTest.apk"):
            if not path.is_file() or "Success" not in adb("Install " + path.name, ["install", "-r", "-t", str(path)]):
                raise RuntimeError("Build candidate/control app and test APKs first")

    def uninstall(package: str):
        for name in (package + ".test", package):
            if f"package:{name}" in adb("Check disposable package", ["shell", "pm", "list", "packages", name]).splitlines():
                if "Success" not in adb("Remove only " + name, ["uninstall", name]):
                    raise RuntimeError("Disposable package removal failed")

    def instrument(package: str, klass: str, filename: str, framework: bool = False) -> str:
        flags = ["--no-hidden-api-checks"] if framework and api >= 28 else []
        output = adb("Verify " + klass.rsplit(".", 1)[-1], ["shell", "am", "instrument", *flags, "-w", "-r", "-e", "class", klass, package + ".test/" + RUNNER])
        (args.output / filename).write_text(output)
        if (not re.search(r"OK \(1 test\)", output) or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output
                or "INSTRUMENTATION_STATUS_CODE: -3" in output or "INSTRUMENTATION_STATUS_CODE: -4" in output):
            raise RuntimeError(klass + " did not pass: " + output[-7000:])
        print(output[output.rfind("Time:"):].strip(), flush=True)
        return output

    api = int(adb("Read test API", ["shell", "getprop", "ro.build.version.sdk"]).strip())
    checks = []
    if not args.backup_only:
        uninstall(CANDIDATE); install("acceptance")
        instrument(CANDIDATE, "com.financegenie.android.ReleaseAcceptanceTest", "release-smoke.log")
        adb("Stop only the candidate for a fresh-process read", ["shell", "am", "force-stop", CANDIDATE])
        instrument(CANDIDATE, "com.financegenie.android.ReleaseAcceptanceReadTest", "release-restart.log")
    else:
        install("acceptance")
    if not args.smoke_only:
        before = adb("Read existing backup setting without changing it", ["shell", "bmgr", "enabled"])
        try:
            install("backupControl")
            for package, filename in ((CANDIDATE, "backup-exclusions.log"), (CONTROL, "backup-positive-control.log")):
                output = instrument(package, "com.financegenie.android.PlatformBackupTest", filename, framework=True)
                result = re.search(r"^INSTRUMENTATION_STATUS: financegenie_backup_extraction=(.+)$", output, re.MULTILINE)
                if not result:
                    raise RuntimeError("Framework backup extraction result missing")
                checks.extend(json.loads(result.group(1)))
        finally:
            uninstall(CONTROL)
        assert before == adb("Verify phone backup setting was preserved", ["shell", "bmgr", "enabled"])
    apk = ROOT / "apps/android/build/outputs/apk/acceptance/android-acceptance.apk"
    report = {"apiLevel": api, "candidateApkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "candidateApkBytes": apk.stat().st_size, "releaseSmoke": not args.backup_only,
              "freshReader": not args.backup_only, "frameworkExtraction": checks,
              "limitations": ["Framework extraction is not a physical OEM device migration or cloud restore.",
                              "The signed candidate has a separate package/label and no debugging; distribution signing is not configured."]}
    (args.output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    output = adb("Open only the synthetic acceptance app", ["shell", "am", "start", "-W", "-n", CANDIDATE + "/com.financegenie.android.MainActivity"])
    assert "Status: ok" in output
    print("PASS: selected release/framework checks; normal FinanceGenie data untouched", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, AssertionError, subprocess.SubprocessError) as error:
        raise SystemExit("Acceptance verification failed: " + str(error))
