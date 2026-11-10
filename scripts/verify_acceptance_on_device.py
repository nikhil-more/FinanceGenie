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
CANDIDATE_APKS = (ROOT / "apps/android/build/outputs/apk/acceptance/android-acceptance.apk",
                  ROOT / "apps/android/build/outputs/apk/androidTest/acceptance/android-acceptance-androidTest.apk")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path, default=Path("/tmp/financegenie-step9-acceptance"))
    parser.add_argument("--reuse-candidate", action="store_true",
                        help="Verify installed candidate/test APK hashes and reset only candidate data, preserving per-install blocker rules")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--smoke-only", action="store_true")
    modes.add_argument("--backup-only", action="store_true")
    args = parser.parse_args()
    ready = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True, timeout=30)
    devices = [p[0] for line in ready.stdout.splitlines() if len(p := line.split()) == 2 and p[1] == "device"]
    serial = args.serial or (devices[0] if len(devices) == 1 else None)
    if serial not in devices:
        raise RuntimeError("Connect one authorized test device or select it with --serial")
    if not all(path.is_file() for path in CANDIDATE_APKS):
        raise RuntimeError("Build the candidate and test APKs before running acceptance")
    artifact_hashes = [hashlib.sha256(path.read_bytes()).hexdigest() for path in CANDIDATE_APKS]
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

    def verify_installed_candidate():
        for package, path in zip((CANDIDATE, CANDIDATE + ".test"), CANDIDATE_APKS):
            location = adb("Locate installed " + package, ["shell", "pm", "path", package]).strip().splitlines()
            if not path.is_file() or len(location) != 1 or not location[0].startswith("package:/"):
                raise RuntimeError("Install the current candidate/test APKs before using --reuse-candidate")
            result = subprocess.run(["adb", "-s", serial, "exec-out", "cat", location[0][len("package:"):]],
                                    capture_output=True, timeout=60)
            if result.returncode or hashlib.sha256(result.stdout).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise RuntimeError("Installed APK differs from this build: " + package)

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
    if args.reuse_candidate:
        verify_installed_candidate()
    checks = []
    if not args.backup_only:
        if args.reuse_candidate:
            if "Success" not in adb("Reset only the disposable candidate's synthetic data", ["shell", "pm", "clear", CANDIDATE]):
                raise RuntimeError("Synthetic candidate reset failed")
        else:
            uninstall(CANDIDATE); install("acceptance")
        instrument(CANDIDATE, "com.financegenie.android.ReleaseAcceptanceTest", "release-smoke.log")
        adb("Stop only the candidate for a fresh-process read", ["shell", "am", "force-stop", CANDIDATE])
        instrument(CANDIDATE, "com.financegenie.android.ReleaseAcceptanceReadTest", "release-restart.log")
    elif not args.reuse_candidate:
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
    if artifact_hashes != [hashlib.sha256(path.read_bytes()).hexdigest() for path in CANDIDATE_APKS]:
        raise RuntimeError("Candidate/test APKs changed during verification; build before starting a new run")
    apk = CANDIDATE_APKS[0]
    report = {"apiLevel": api, "candidateInstallation": "reused" if args.reuse_candidate else "installed by driver",
              "candidateResetMethod": None if args.backup_only else ("pm-clear" if args.reuse_candidate else "uninstall-reinstall"),
              "candidateApkSha256": artifact_hashes[0], "candidateTestApkSha256": artifact_hashes[1],
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
