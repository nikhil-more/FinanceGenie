#!/usr/bin/env python3
"""Small dependency-free hygiene guard, not a full formatter or secret scanner."""

import hashlib
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
TEXT_SUFFIXES = {".kt", ".kts", ".xml", ".toml", ".yml", ".yaml", ".py", ".properties"}
PRIVATE_NAMES = {"local.properties", "keystore.properties", "google-services.json", ".env"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".pem", ".key"}
SECRET_PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "Google API key": re.compile(r"\bAIza[A-Za-z0-9_-]{35}\b"),
}


def main() -> int:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    errors = []
    paths = sorted(set(result.stdout.decode().split("\0")) - {""})
    for relative in paths:
        path = ROOT / relative
        if not path.is_file():
            continue
        if path.name in PRIVATE_NAMES or path.suffix in PRIVATE_SUFFIXES:
            errors.append(f"{relative}: private configuration must not be versioned")
            continue
        raw = path.read_bytes()
        try:
            content = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        for label, pattern in SECRET_PATTERNS.items():
            if pattern.search(content):
                errors.append(f"{relative}: possible {label}; value withheld")
        if path.suffix in TEXT_SUFFIXES or path.name == ".editorconfig":
            if raw and not raw.endswith(b"\n"):
                errors.append(f"{relative}: missing final newline")
            if b"\r" in raw:
                errors.append(f"{relative}: expected LF line endings")
            for number, line in enumerate(content.splitlines(), 1):
                if line.rstrip() != line or "\t" in line:
                    errors.append(f"{relative}:{number}: trailing whitespace or tab")
        try:
            if path.suffix == ".xml":
                ET.fromstring(content)
            elif path.suffix == ".toml":
                tomllib.loads(content)
        except (ET.ParseError, tomllib.TOMLDecodeError) as error:
            errors.append(f"{relative}: invalid syntax ({type(error).__name__})")

    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if not wrapper.is_file() or hashlib.sha256(wrapper.read_bytes()).hexdigest() != WRAPPER_SHA256:
        errors.append("Gradle wrapper JAR does not match the published Gradle 8.9 checksum")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Repository hygiene passed for {len(paths)} files; wrapper checksum verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
