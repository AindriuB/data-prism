#!/usr/bin/env bash
# AC10 / AC18: run LinkTimingSoakTest (sealed-links.md section 7 item 10) with
# -Ddataprism.timing=true and print its result and tolerance in the form the LinkTimingSoakTest
# table of soak-0.8.md takes: one line, "result | tolerance | test seconds".
# On a failure the line also carries the medians the assertion reports. Nothing else of the run is kept.
# Usage: link-timing.sh   (from anywhere; run it on the soak host)
set -euo pipefail
root="$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_src="$root/data-prism-orchestration/src/test/java/io/github/aindriub/dataprism/orchestration/links/LinkTimingSoakTest.java"
report_dir="$root/data-prism-orchestration/target/surefire-reports"

rel="$(grep -o 'RELATIVE_TOLERANCE = [0-9.]*' "$test_src" | grep -o '[0-9.]*$')"
abs="$(grep -o 'ABSOLUTE_TOLERANCE_NANOS = [0-9_]*' "$test_src" | grep -o '[0-9_]*$' | tr -d _)"
samples="$(grep -o ' SAMPLES = [0-9_]*' "$test_src" | grep -o '[0-9_]*$' | tr -d _)"

rm -f "$report_dir"/TEST-*LinkTimingSoakTest.xml
status=pass
(cd "$root" && ./mvnw -B -q -pl data-prism-orchestration -am -Dtest=LinkTimingSoakTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Ddataprism.timing=true test >/dev/null 2>&1) || status=fail

report="$(ls "$report_dir"/TEST-*LinkTimingSoakTest.xml 2>/dev/null | head -1 || true)"
if [ -z "$report" ]; then echo "not run | none | none"; exit 1; fi
ran="$(grep -o 'tests="[0-9]*"' "$report" | head -1 | grep -o '[0-9]*')"
skipped="$(grep -o 'skipped="[0-9]*"' "$report" | head -1 | grep -o '[0-9]*')"
secs="$(grep -o '<testcase [^>]*time="[0-9.]*"' "$report" | grep -o 'time="[0-9.]*"' | head -1 | grep -o '[0-9.]*')"
[ "${skipped:-0}" -gt 0 ] && status="skipped (timing flag not honoured)"
medians=""
if [ "$status" = fail ]; then medians="; $(grep -o 'medians \[[0-9, ]*\] ns' "$report" | head -1)"; fi
echo "$status (tests run: $ran)$medians | medians within ${rel} x the smallest + ${abs} ns, $samples samples per class | ${secs:-none} s"
[ "$status" = pass ]
