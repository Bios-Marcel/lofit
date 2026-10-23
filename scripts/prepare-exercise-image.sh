#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 INPUT_IMAGE exercise_resource_name" >&2
  exit 2
fi

input=$1
name=$2

if [[ ! -f "$input" ]]; then
  echo "input not found: $input" >&2
  exit 2
fi

if [[ ! "$name" =~ ^exercise_[a-z0-9_]+$ ]]; then
  echo "name must match exercise_[a-z0-9_]+" >&2
  exit 2
fi

if ! command -v magick >/dev/null 2>&1; then
  echo "ImageMagick 'magick' is required" >&2
  exit 1
fi

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output="$root/app/src/main/res/drawable-nodpi/$name.webp"

magick "$input" \
  -auto-orient \
  -resize '627x314^' \
  -gravity center \
  -extent 627x314 \
  -strip \
  -quality 82 \
  "$output"

echo "$output"
