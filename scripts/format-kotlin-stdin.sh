#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(sed -n 's/^ktfmtVersion=//p' "$root/gradle.properties")"
jar="$root/build/formatter/$version/ktfmt-$version-with-dependencies.jar"

if [[ ! -f "$jar" ]]; then
    "$root/gradlew" --quiet --project-dir "$root" prepareKtfmtCli >&2
fi

exec java -jar "$jar" --kotlinlang-style -
