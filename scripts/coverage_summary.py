#!/usr/bin/env python3
"""Print a Markdown coverage summary from the JaCoCo XML report.

Used by .github/workflows/android-tests.yml to put the numbers on the run's
summary page, and runnable by hand after `./gradlew coverageReport`.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORT = Path("app/build/reports/jacoco/coverageReport/coverageReport.xml")


def counters(node):
    return {
        counter.get("type"): (int(counter.get("covered")), int(counter.get("missed")))
        for counter in node.findall("counter")
    }


def percentage(covered, missed):
    total = covered + missed
    return 100.0 * covered / total if total else 0.0


def main():
    if not REPORT.is_file():
        print(f"## Test coverage\n\nNo report at `{REPORT}`.\n")
        return 1

    root = ET.parse(REPORT).getroot()
    overall = counters(root)

    print("## Test coverage\n")
    for kind in ("LINE", "BRANCH", "CLASS", "METHOD"):
        covered, missed = overall.get(kind, (0, 0))
        total = covered + missed
        print(f"- **{kind.title()}**: {percentage(covered, missed):.1f}% ({covered}/{total})")

    rows = []
    for package in root.findall("package"):
        stats = counters(package)
        rows.append(
            (
                percentage(*stats.get("LINE", (0, 0))),
                package.get("name"),
                percentage(*stats.get("BRANCH", (0, 0))),
            )
        )

    print("\n### By package\n")
    print("| Package | Lines | Branches |")
    print("| --- | --- | --- |")
    for line_pct, name, branch_pct in sorted(rows, reverse=True):
        print(f"| `{name}` | {line_pct:.1f}% | {branch_pct:.1f}% |")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
