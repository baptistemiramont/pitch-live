#!/usr/bin/env bash
# Downloads the tracking data of one of Metrica Sports' sample matches.
# Source : https://github.com/metrica-sports/sample-data
# Usage: scripts/download-data.sh [1|2]   (match 1 by default)
set -euo pipefail

GAME="${1:-1}"
if [[ "$GAME" != "1" && "$GAME" != "2" ]]; then
  echo "Only matches 1 and 2 are available in CSV." >&2
  exit 1
fi

BASE_URL="https://raw.githubusercontent.com/metrica-sports/sample-data/master/data/Sample_Game_${GAME}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/data/Sample_Game_${GAME}"
mkdir -p "$DEST"

for team in Home Away; do
  file="Sample_Game_${GAME}_RawTrackingData_${team}_Team.csv"
  if [[ -s "$DEST/$file" ]]; then
    echo "Already there: $file"
    continue
  fi
  echo "Downloading $file"
  curl --fail --location --silent --show-error --output "$DEST/$file.part" "$BASE_URL/$file"
  mv "$DEST/$file.part" "$DEST/$file"
done
