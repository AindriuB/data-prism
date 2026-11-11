#!/usr/bin/env bash
# Prints the image ID the three members run, for the driver's target-mode result:
#   export SOAK_MEMBER_IMAGE_ID="$(load-test/soak/member-image-id.sh)"
# One ID when all three agree, otherwise the three joined with "+" (a mixed-build cluster is a finding). Prints an
# ID and nothing else.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ids=""
for m in "${members[@]}"; do ids="$ids $(member_image_id "$m")"; done
set -- $ids
if [ "$1" = "$2" ] && [ "$2" = "$3" ]; then printf '%s\n' "$1"; else printf '%s+%s+%s\n' "$1" "$2" "$3"; fi
