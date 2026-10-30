#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ANDROID_SERIAL=RFCTC0T2CYP "$SCRIPT_DIR/deploy.sh"; then
    exit 0
fi

echo "Replug phone, then run ./scripts/deploy-phone.sh again." >&2
exit 1
