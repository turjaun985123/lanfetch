#!/usr/bin/env python3
"""Version consistency gate (ADR-005): every version carrier in
desktop-tauri/ must agree with package.json. Stdlib only."""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def first_cargo_version(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r'version\s*=\s*"([^"]+)"', line)
            if m:
                return m.group(1)
    return None


def lock_versions(path, names):
    found = {}
    current = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r'name\s*=\s*"([^"]+)"', line)
            if m:
                current = m.group(1)
            m = re.match(r'version\s*=\s*"([^"]+)"', line)
            if m and current in names:
                found[current] = m.group(1)
                current = None
    return found


def main():
    with open(os.path.join(ROOT, "package.json"), encoding="utf-8") as f:
        expected = json.load(f)["version"]

    checks = {}
    with open(os.path.join(ROOT, "package-lock.json"), encoding="utf-8") as f:
        lock = json.load(f)
    checks["package-lock.json (top-level)"] = lock.get("version")
    checks["package-lock.json (packages[\"\"])"] = (
        lock.get("packages", {}).get("", {}).get("version")
    )
    for crate in ("src-tauri", "engine", "web"):
        checks[f"{crate}/Cargo.toml"] = first_cargo_version(
            os.path.join(ROOT, crate, "Cargo.toml")
        )
    for name, ver in lock_versions(
        os.path.join(ROOT, "Cargo.lock"),
        {"lanfetch-engine", "lanfetch-tauri", "lanfetch-web"},
    ).items():
        checks[f"Cargo.lock ({name})"] = ver

    failures = [
        f"{where}: found {found!r}, expected {expected!r}"
        for where, found in checks.items()
        if found != expected
    ]

    # The release notes are generated from the tag (Wave 1 fix), so on a
    # tag run the tag itself is a version carrier. Release candidates
    # carry an -rcN suffix on the same version and are equally valid.
    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        tag = os.environ.get("GITHUB_REF_NAME", "")
        if not re.fullmatch(
            rf"desktop-tauri-v{re.escape(expected)}(-rc\d+)?", tag
        ):
            failures.append(
                f"release tag: found {tag!r}, "
                f"expected 'desktop-tauri-v{expected}' (optionally -rcN)"
            )

    if failures:
        print("Version consistency check FAILED (ADR-005):")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print(f"Version consistency OK: everything is {expected}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
