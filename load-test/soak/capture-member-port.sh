#!/usr/bin/env bash
# AC8 / AC16: capture the member port (5701) of one member for a named transport profile, while
# a scenario drives load, and summarise the capture as counts and findings.
#
# Usage: capture-member-port.sh <profile> [platform-label] [seconds]
#   profile         E1, E2 or E3, as named in docs/plan/design/cluster-security.md 2.6. Only the label
#                   is recorded; the deployment must already be in that profile (E3 means a mesh or
#                   other transport protection the owner has put in front of the member port).
#   platform-label  a free label for the platform, for example linux-x86_64; default "unlabelled".
#   seconds         capture length, default 120.
# Capture runs in a throwaway sidecar sharing server-a's network namespace (CAPTURE_IMAGE, default a
# stock network-tools image with tcpdump), so nothing is installed on the host or the member.
#
# The raw capture is written only under load-test/results/ (git-ignored) or SOAK_RESULTS_DIR when
# set outside the repository. It is never committed. The summary holds counts and findings only:
#   - packets captured on the member port,
#   - whether plaintext appears (a readable Data Prism map-name string in the stream),
#   - whether a subject id appears in the clear (the load harness's subject shape).
# It holds no address, no payload byte and no key. Exit status: 0 on a summary written.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
profile="${1:?usage: $0 <E1|E2|E3> [platform-label] [seconds]}"
platform="${2:-unlabelled}"; secs="${3:-120}"
case "$profile" in E1|E2|E3) ;; *) echo "profile must be E1, E2 or E3" >&2; exit 2 ;; esac
case "$platform" in *[!A-Za-z0-9._-]*) echo "platform label: letters, digits, . _ - only" >&2; exit 2 ;; esac
image="${CAPTURE_IMAGE:-nicolaka/netshoot}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
pcap="capture-$profile-$platform-$stamp.pcap"
out="$(summary_file "capture-$profile-$platform")"; : > "$out"

cid="$(dc ps -q server-a)"
[ -n "$cid" ] || { note "$out" "capture: not run (server-a is not up)"; exit 3; }

docker run --rm --network "container:$cid" --cap-add NET_ADMIN --cap-add NET_RAW \
  -v "$results_dir:/out" "$image" \
  timeout "$secs" tcpdump -i any -n -s 0 -U -w "/out/$pcap" "tcp port 5701" >/dev/null 2>&1 || true

if [ ! -s "$results_dir/$pcap" ]; then note "$out" "capture: no packets recorded (capture rights or image missing?)"; exit 3; fi

# Analyse inside a sidecar so the host needs no tools; only counts leave it.
subject_prefix="${SUBJECT_PREFIX:-subject}"   # the load harness's subject id prefix; adjust if it differs
counts="$(docker run --rm -v "$results_dir:/out:ro" "$image" sh -c '
  f="/out/'"$pcap"'"
  packets=$(tcpdump -n -r "$f" 2>/dev/null | wc -l)
  readable=$(strings -n 6 "$f" | grep -c "dataprism\." || true)
  subjects=$(strings -n 6 "$f" | grep -Ec "'"$subject_prefix"'-[0-9]{8}" || true)
  echo "$packets $readable $subjects"' 2>/dev/null)"
set -- $counts
packets="${1:-0}"; readable="${2:-0}"; subjects="${3:-0}"
note "$out" "profile: $profile; platform: $platform; capture seconds: $secs"
note "$out" "packets on the member port: $packets"
note "$out" "plaintext map-name strings found: $readable; plaintext appears: $([ "$readable" -gt 0 ] && echo yes || echo no)"
note "$out" "subject-id matches in the clear: $subjects; subject id in the clear: $([ "$subjects" -gt 0 ] && echo yes || echo no)"
if [ "$profile" = "E3" ]; then
  note "$out" "expected for E3: no plaintext"
else
  note "$out" "expected for $profile in 1.0: plaintext for approvals and counters; no subject id in the clear (sealed entries)"
fi
note "$out" "raw capture kept under the results directory, not committed"
