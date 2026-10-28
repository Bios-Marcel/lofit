#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties")"
EMULATOR="$SDK_DIR/emulator/emulator"
ADB="$SDK_DIR/platform-tools/adb"

if [[ ! -x "$EMULATOR" || ! -x "$ADB" ]]; then
    echo "Android emulator or adb not found in $SDK_DIR" >&2
    exit 1
fi

if [[ $# -gt 1 ]]; then
    echo "Usage: $0 [AVD_NAME]" >&2
    exit 2
fi

mapfile -t AVDS < <("$EMULATOR" -list-avds)
if [[ $# -eq 1 ]]; then
    AVD_NAME="$1"
    FOUND=false
    for avd in "${AVDS[@]}"; do
        if [[ "$avd" == "$AVD_NAME" ]]; then
            FOUND=true
            break
        fi
    done
    if [[ "$FOUND" != true ]]; then
        echo "AVD not found: $AVD_NAME" >&2
        exit 1
    fi
else
    if [[ ${#AVDS[@]} -ne 1 ]]; then
        echo "Need one AVD. Or pass its name: $0 AVD_NAME" >&2
        exit 1
    fi
    AVD_NAME="${AVDS[0]}"
fi

if ! "$ADB" devices > /dev/null; then
    echo "adb failed. Run this script outside the sandbox if local sockets are blocked." >&2
    exit 1
fi

find_device() {
    local serial name
    while read -r serial _; do
        [[ "$serial" == emulator-* ]] || continue
        name="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -n 1 | tr -d '\r' || true)"
        if [[ "$name" == "$AVD_NAME" ]]; then
            printf '%s\n' "$serial"
            return 0
        fi
    done < <("$ADB" devices)
    return 1
}

SERIAL="$(find_device || true)"
if [[ -z "$SERIAL" ]]; then
    LOG_FILE="${TMPDIR:-/tmp}/lofit-emulator-${AVD_NAME}.log"
    # A separate session keeps the emulator alive after this script exits.
    nohup setsid -f "$EMULATOR" -avd "$AVD_NAME" > "$LOG_FILE" 2>&1 < /dev/null
fi

for ((i = 0; i < 60; i++)); do
    SERIAL="$(find_device || true)"
    if [[ -n "$SERIAL" && "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        echo "Emulator ready: $SERIAL ($AVD_NAME)"
        exit 0
    fi
    sleep 2
done

echo "Emulator did not boot: $AVD_NAME. Check ${LOG_FILE:-the emulator window}." >&2
exit 1
