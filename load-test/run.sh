#!/usr/bin/env bash
# Builds the server from this checkout, builds the harness, runs one scenario and writes
# load-test/results/<scenario>-<timestamp>.json. See load-test/README.md.
#
#   load-test/run.sh load-test/scenarios/sources-8.json
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: load-test/run.sh <scenario.json>" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scenario="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

# The real server and the one extension the harness loads onto it, built from this checkout.
(cd "$root" && ./mvnw -B -q -pl data-prism-server,data-prism-connectors-rest -am -DskipTests package)
# The harness, with its own unit tests; it is not part of the root reactor.
(cd "$root" && ./mvnw -B -q -f load-test/pom.xml package)

cd "$root/load-test"
exec java -jar target/data-prism-load-test.jar "$scenario" --repo-root "$root" --results-dir "$root/load-test/results"
