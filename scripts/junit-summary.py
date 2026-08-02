#!/usr/bin/env python3
"""Aggregate Maven surefire reports into a single pass/fail summary.

Usage:
  scripts/junit-summary.py [surefire-reports-dir]

Reads every `*.txt` report (default: target/surefire-reports), sums the
Tests run / Failures / Errors / Skipped counters, and lists the classes that
failed. Exit code is non-zero when any test failed or errored, so it doubles as
a CI gate after `mvn test`.
"""
import pathlib
import re
import sys

COUNTS = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)")


def main() -> int:
    reports_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports")
    if not reports_dir.is_dir():
        print(f"no surefire reports at {reports_dir}", file=sys.stderr)
        return 2
    reports = sorted(reports_dir.glob("*.txt"))
    tests = failures = errors = skipped = 0
    failed = []
    for report in reports:
        match = COUNTS.search(report.read_text())
        if not match:
            continue
        tests += int(match[1])
        failures += int(match[2])
        errors += int(match[3])
        skipped += int(match[4])
        if int(match[2]) + int(match[3]):
            failed.append(report.stem)
    print(f"classes={len(reports)} tests={tests} failures={failures} errors={errors} skipped={skipped}")
    print("FAILED:", ", ".join(failed) if failed else "none")
    return 1 if (failures or errors) else 0


if __name__ == "__main__":
    sys.exit(main())
