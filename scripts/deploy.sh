#!/usr/bin/env bash

set -euo pipefail

source "$(dirname "$0")/android-device.sh"

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:assembleDebug
"$ADB" -s "$ANDROID_SERIAL" install -r "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE_NAME"
"$ADB" -s "$ANDROID_SERIAL" shell am start -n "$ACTIVITY_NAME"
