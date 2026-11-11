#!/usr/bin/env bash
# AC3: a member of the three-member cluster started with dataprism.links.enabled=true, and one
# started with a link key configured, must each refuse at startup with MISSING_SHARED_LINK_LIMITER.
# server-c is re-created twice against the running cluster, once per probe, then restored. The
# link key is generated here, held only in the probe's own environment, and never printed.
# Writes counts and findings only: whether the refusal code appeared in the member's log.
# Usage: probe-startup-refusals.sh
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_keys
out="$(summary_file probe-startup-refusals)"; : > "$out"
provenance_begin "$out"

# However the script ends (a probe failing, an interrupt), put server-c back as the overlay defines it.
restored=0
restore_server_c() { [ "$restored" = 1 ] || dc up -d --force-recreate --no-deps server-c >/dev/null 2>&1 || true; }
on_exit restore_server_c

probe() {   # probe <label> <env override file content>
  local label="$1" override; override="$(mktemp)"
  printf '%s\n' "$2" > "$override"
  # The probe is server-c's definition, re-created with the override; the cluster keeps running.
  dc -f "$override" up -d --force-recreate --no-deps server-c >/dev/null 2>&1 || true
  sleep 60
  local hits; hits="$(dc logs --no-log-prefix server-c 2>/dev/null | grep -c 'MISSING_SHARED_LINK_LIMITER' || true)"
  note "$out" "$label: MISSING_SHARED_LINK_LIMITER log lines: $hits; refused: $([ "$hits" -gt 0 ] && echo yes || echo no)"
  rm -f "$override"
}

probe "links enabled" "services:
  server-c:
    environment:
      DATAPRISM_LINKS_ENABLED: \"true\""

export DATAPRISM_PROBE_LINK_KEY_MATERIAL
DATAPRISM_PROBE_LINK_KEY_MATERIAL="$(openssl rand -hex 32)"
probe "link key configured" "services:
  server-c:
    environment:
      DATAPRISM_LINKS_KEYID: probe
      DATAPRISM_LINKS_KEYS_PROBE_ENVIRONMENTVARIABLE: DATAPRISM_PROBE_LINK_KEY_MATERIAL
      DATAPRISM_PROBE_LINK_KEY_MATERIAL: \${DATAPRISM_PROBE_LINK_KEY_MATERIAL}"

# Put server-c back as the overlay defines it. The exit cleanup then does not recreate it a second time; it
# covers only an exit before this point.
restore_server_c
restored=1
note "$out" "server-c restored to the overlay definition"
