#!/usr/bin/env bash
# Usage: check-class-version.sh <expected-major> <jar>...
#
# Fails if any class in any jar has a class-file major version other than the
# expected one (65 = Java 21, 69 = Java 25). Guards against a newer JDK
# toolchain shipping bytecode that Java 21 consumers cannot load.
#
# Skipped: META-INF/versions/** (multi-release entries are meant to differ) and
# module-info.class of nested third-party jars. For a Spring Boot fat jar only
# the jar's own BOOT-INF/classes/** is checked; the loader and BOOT-INF/lib are
# third-party and are not ours to judge.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <expected-major> <jar>..." >&2
  exit 2
fi
expected="$1"
shift

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

status=0
for jar in "$@"; do
  if [ ! -f "$jar" ]; then
    echo "::error::jar not found: $jar" >&2
    status=1
    continue
  fi
  dir="$tmp/$(basename "$jar").x"
  mkdir -p "$dir"
  unzip -q -o "$jar" '*.class' -d "$dir" 2>/dev/null || true

  if [ -d "$dir/BOOT-INF/classes" ]; then
    root="$dir/BOOT-INF/classes"
  else
    root="$dir"
  fi

  checked=0
  while IFS= read -r -d '' cls; do
    rel="${cls#"$dir"/}"
    case "$rel" in
      META-INF/versions/*) continue ;;
      BOOT-INF/lib/*) continue ;;
    esac
    # bytes 6-7 (0-based) are the big-endian major version
    major="$(od -An -tu1 -j6 -N2 "$cls" | awk 'NF >= 2 {print $1 * 256 + $2; exit}')"
    checked=$((checked + 1))
    if [ "$major" != "$expected" ]; then
      echo "::error::$jar: $rel has class-file major $major, expected $expected" >&2
      status=1
    fi
  done < <(find "$root" -name '*.class' -type f -print0)

  if [ "$checked" -eq 0 ]; then
    # Test-only and aggregator modules legitimately ship no classes.
    echo "$jar: no classes to check"
  else
    echo "$jar: $checked classes checked against major $expected"
  fi
done
exit "$status"
