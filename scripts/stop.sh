#!/usr/bin/env bash

set -euo pipefail

source "$(dirname "$0")/android-device.sh"

"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE_NAME"
