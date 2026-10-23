#!/usr/bin/env bash

set -euo pipefail

ANDROID_SERIAL="${ANDROID_SERIAL:-RFCTC0T2CYP}"
PACKAGE_NAME="lofit.app"
ACTIVITY_NAME="$PACKAGE_NAME/lofit.MainActivity"

source "$(dirname "$0")/android-device.sh"

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:assembleRelease
"$ADB" -s "$ANDROID_SERIAL" install -r "$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE_NAME"
"$ADB" -s "$ANDROID_SERIAL" shell am start -n "$ACTIVITY_NAME"
