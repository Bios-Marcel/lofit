#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties")"
ADB="$SDK_DIR/platform-tools/adb"

if [[ -z "${ANDROID_SERIAL:-}" || "$ANDROID_SERIAL" == emulator-* ]]; then
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        EMULATOR_RUNNING="$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)"
    else
        EMULATOR_RUNNING="$("$ADB" devices | awk 'NR > 1 && $1 ~ /^emulator-/ && $2 == "device" { print "device"; exit }')"
    fi
    if [[ "$EMULATOR_RUNNING" != device ]]; then
        "$SCRIPT_DIR/start-emulator.sh"
    fi
    if [[ -z "${ANDROID_SERIAL:-}" ]]; then
        mapfile -t EMULATOR_SERIALS < <("$ADB" devices | awk 'NR > 1 && $1 ~ /^emulator-/ && $2 == "device" { print $1 }')
        if [[ ${#EMULATOR_SERIALS[@]} -eq 1 ]]; then
            export ANDROID_SERIAL="${EMULATOR_SERIALS[0]}"
        fi
    fi
fi

source "$SCRIPT_DIR/android-device.sh"

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:assembleDebug
"$ADB" -s "$ANDROID_SERIAL" install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE_NAME"
"$ADB" -s "$ANDROID_SERIAL" shell am start -n "$ACTIVITY_NAME"
