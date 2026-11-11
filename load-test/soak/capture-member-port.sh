#!/usr/bin/env bash
# AC8 / AC16: capture the member port (5701) of one member for a named transport profile, while
# a scenario drives load, and summarise the capture as counts and findings.
#
# Usage: SUBJECT_IDS=<id>,<id>,... capture-member-port.sh <profile> [platform-label] [seconds]
#   profile         E1, E2 or E3, as named in docs/plan/design/cluster-security.md 2.6. Only the label
#                   is recorded; the deployment must already be in that profile (E3 means a mesh or
#                   other transport protection the owner has put in front of the member port).
#   platform-label  a free label for the platform, for example linux-x86_64; default "unlabelled".
#   seconds         capture length, default 120.
#   SUBJECT_IDS     required: the exact subject ids the scenario under capture calls (the `subjects` of
#                   soak-three-member.json, or the ids a generated run uses), comma separated. The check looks
#                   for exactly these strings in the raw capture (grep -aF, no minimum length), so ids of any
#                   length, such as 1001, are searched for. A short id can also match by chance in encrypted
#                   bytes: a "yes" is a finding to read, not a verdict.
# The check proves itself first: a positive control appends the first id to a copy of the capture and the same
# search must find it, otherwise no result is written ("not run").
# Capture runs in a throwaway sidecar sharing server-a's network namespace. The image is pinned by digest
# (CAPTURE_IMAGE overrides it) and the sidecar has NET_RAW (and SETUID and SETGID, for tcpdump to drop privileges): not NET_ADMIN, which would
# let it change the member's network. The analysis runs with no network and no capabilities.
#
# The raw capture is written only under load-test/results/ (git-ignored) or SOAK_RESULTS_DIR when
# set outside the repository. It is never committed. The summary holds counts and findings only:
#   - packets captured on the member port,
#   - whether plaintext appears (a readable Data Prism map-name string in the stream),
#   - whether a given subject id appears in the clear, and that the check's control passed.
# It holds no address, no payload byte and no key. Exit status: 0 on a summary written.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
profile="${1:?usage: SUBJECT_IDS=<ids> $0 <E1|E2|E3> [platform-label] [seconds]}"
subject_ids="${SUBJECT_IDS:?set SUBJECT_IDS to the subject ids the scenario calls, comma separated}"
platform="${2:-unlabelled}"; secs="${3:-120}"
case "$profile" in E1|E2|E3) ;; *) echo "profile must be E1, E2 or E3" >&2; exit 2 ;; esac
case "$platform" in *[!A-Za-z0-9._-]*) echo "platform label: letters, digits, . _ - only" >&2; exit 2 ;; esac
image="${CAPTURE_IMAGE:-nicolaka/netshoot@sha256:b09d9b21381f47a79b3cbcb30da25266dc17186ea00ae65e99fdc51396f48e70}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
pcap="capture-$profile-$platform-$stamp.pcap"
out="$(summary_file "capture-$profile-$platform")"; : > "$out"

cid="$(dc ps -q server-a)"
[ -n "$cid" ] || { note "$out" "capture: not run (server-a is not up)"; exit 3; }

# The sidecar writes its pcap inside its own filesystem (/tmp), which needs no host-directory rights: a rootful
# Linux engine would refuse a write into the owner-owned results directory without CAP_DAC_OVERRIDE. The file is
# copied out with docker cp afterwards. tcpdump's own error output is kept and shown if nothing was recorded.
cap_name="soak-capture-$stamp-$$"
trap 'docker rm -f "$cap_name" >/dev/null 2>&1 || true' EXIT
docker run --name "$cap_name" --network "container:$cid" --cap-drop ALL --cap-add NET_RAW --cap-add SETUID --cap-add SETGID \
  "$image" timeout "$secs" tcpdump -i any -n -s 0 -U -w "/tmp/$pcap" "tcp port 5701" >/dev/null 2>"$results_dir/.capture-$stamp.err" || true
docker cp "$cap_name:/tmp/$pcap" "$results_dir/$pcap" >/dev/null 2>>"$results_dir/.capture-$stamp.err" || true

if [ ! -s "$results_dir/$pcap" ]; then
  note "$out" "capture: no packets recorded (capture rights or image missing?)"
  sed -n '1,5p' "$results_dir/.capture-$stamp.err" >&2
  exit 3
fi
rm -f "$results_dir/.capture-$stamp.err"

# Analyse in a sidecar with no network and no capabilities; only counts leave it.
counts="$(docker run --rm --network none --cap-drop ALL -e "SUBJECT_IDS=$subject_ids" \
  -v "$results_dir:/out:ro" "$image" sh -c '
  f="/out/'"$pcap"'"
  printf "%s\n" "$SUBJECT_IDS" | tr "," "\n" | grep -v "^$" > /tmp/ids
  packets=$(tcpdump -n -r "$f" 2>/dev/null | wc -l)
  readable=$(strings -n 6 "$f" | grep -c "dataprism\." || true)
  subjects=$(grep -aFo -f /tmp/ids "$f" | wc -l)
  head -n 1 /tmp/ids > /tmp/ctl
  cat "$f" /tmp/ctl > /tmp/with-control
  control=$(grep -aFo -f /tmp/ids /tmp/with-control | wc -l)
  [ "$control" -gt "$subjects" ] && ok=yes || ok=no
  echo "$packets $readable $subjects $ok"' 2>/dev/null)"
set -- $counts
packets="${1:-0}"; readable="${2:-0}"; subjects="${3:-0}"; control="${4:-no}"
if [ "$control" != "yes" ]; then note "$out" "capture: not run (the subject-id check failed its positive control)"; exit 3; fi
note "$out" "profile: $profile; platform: $platform; capture seconds: $secs"
note "$out" "packets on the member port: $packets"
note "$out" "plaintext map-name strings found: $readable; plaintext appears: $([ "$readable" -gt 0 ] && echo yes || echo no)"
note "$out" "subject-id check control passed: yes; exact matches of the run's subject ids in the clear: $subjects; subject id in the clear: $([ "$subjects" -gt 0 ] && echo yes || echo no)"
if [ "$profile" = "E3" ]; then
  note "$out" "expected for E3: no plaintext"
else
  note "$out" "expected for $profile in 1.0: plaintext for approvals and counters; no subject id in the clear (sealed entries)"
fi
note "$out" "raw capture kept under the results directory, not committed"
