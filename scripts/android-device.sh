#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties")"
ADB="$SDK_DIR/platform-tools/adb"
PACKAGE_NAME="lofit.app"
ACTIVITY_NAME="$PACKAGE_NAME/lofit.MainActivity"

if [[ ! -x "$ADB" ]]; then
    echo "adb not found: $ADB" >&2
    exit 1
fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    mapfile -t DEVICES < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ ${#DEVICES[@]} -ne 1 ]]; then
        echo "Need one device. Or set ANDROID_SERIAL." >&2
        exit 1
    fi
    ANDROID_SERIAL="${DEVICES[0]}"
fi

export ROOT_DIR ADB PACKAGE_NAME ACTIVITY_NAME ANDROID_SERIAL
