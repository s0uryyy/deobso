#!/usr/bin/env python3
"""Publish aggregate JUnit results without consuming Actions artifact storage."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(directory):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    reports = sorted(Path(directory).glob("TEST-*.xml"))
    if not reports:
        return "## Core tests\n\nNo JUnit XML reports found. Check the Gradle step; this is not a successful test result.\n"
    for report in reports:
        root = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(root.get(key, "0"))
    return ("## Core tests\n\n"
            + " | ".join(f"{key}: **{value}**" for key, value in totals.items())
            + "\n\nReports were summarized without uploading artifacts. The Gradle step determines test success.\n")


if __name__ == "__main__":
    summary = summarize("tests/build/test-results/test")
    print(summary)
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
        output.write(summary)
