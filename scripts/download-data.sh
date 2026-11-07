#!/usr/bin/env bash
# Télécharge les données de suivi d'un match d'exemple de Metrica Sports.
# Source : https://github.com/metrica-sports/sample-data
# Usage : scripts/download-data.sh [1|2]   (match 1 par défaut)
set -euo pipefail

GAME="${1:-1}"
if [[ "$GAME" != "1" && "$GAME" != "2" ]]; then
  echo "Seuls les matchs 1 et 2 sont en CSV." >&2
  exit 1
fi

BASE_URL="https://raw.githubusercontent.com/metrica-sports/sample-data/master/data/Sample_Game_${GAME}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/data/Sample_Game_${GAME}"
mkdir -p "$DEST"

for team in Home Away; do
  file="Sample_Game_${GAME}_RawTrackingData_${team}_Team.csv"
  if [[ -s "$DEST/$file" ]]; then
    echo "Déjà présent : $file"
    continue
  fi
  echo "Téléchargement de $file"
  curl --fail --location --silent --show-error --output "$DEST/$file.part" "$BASE_URL/$file"
  mv "$DEST/$file.part" "$DEST/$file"
done
