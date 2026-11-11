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

root="$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scenario="$(CDPATH= cd "$(dirname "$1")" && pwd)/$(basename "$1")"

# The real server and the one extension the harness loads onto it, built from this checkout.
(cd "$root" && ./mvnw -B -q -pl data-prism-server,data-prism-connectors-rest -am -DskipTests package)
# The harness compiles against the reactor's data-prism-core jar (system scope), so it is built for the
# reactor's version, read from the root pom, never a version written in load-test/pom.xml.
version="$(sed -n 's/^  <version>\(.*\)<\/version>$/\1/p' "$root/pom.xml" | head -n 1)"
[ -n "$version" ] || { echo "run.sh: cannot read the reactor version from $root/pom.xml" >&2; exit 1; }
core="$root/data-prism-core/target/data-prism-core-$version.jar"
[ -f "$core" ] || { echo "run.sh: missing $core (the reactor build did not produce data-prism-core $version)" >&2; exit 1; }
# The harness, with its own unit tests; it is not part of the root reactor.
(cd "$root" && ./mvnw -B -q -f load-test/pom.xml -Ddataprism.version="$version" package)

cd "$root/load-test"
exec java -jar target/data-prism-load-test.jar "$scenario" --repo-root "$root" --results-dir "$root/load-test/results"
