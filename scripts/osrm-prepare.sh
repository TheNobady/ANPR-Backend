#!/usr/bin/env bash
#
# One-time preparation of the local routing engine.
#
# The road graph generator asks for a driving route between every camera
# pair inside 3 km. Against the public OSRM demo server that means a
# 150 ms politeness delay plus network latency per pair; locally it is a
# few milliseconds, and the whole graph builds in seconds.
#
#   ./scripts/osrm-prepare.sh [region-url]
#
# Downloads the extract if it is not already present, then runs the
# three OSRM stages. Safe to re-run: each stage is skipped if its output
# is newer than its input.

set -euo pipefail

DATA_DIR="$(cd "$(dirname "$0")/.." && pwd)/osrm-data"
REGION_URL="${1:-https://download.geofabrik.de/asia/india/northern-zone-latest.osm.pbf}"
PBF="$DATA_DIR/region.osm.pbf"
OSRM="$DATA_DIR/region.osrm"
IMAGE="osrm/osrm-backend:latest"

mkdir -p "$DATA_DIR"

if [[ ! -s "$PBF" ]]; then
  echo "==> downloading $REGION_URL"
  curl -L --retry 3 --fail -o "$PBF" "$REGION_URL"
else
  echo "==> extract already present ($(du -h "$PBF" | cut -f1))"
fi

run() { docker run --rm -t -v "$DATA_DIR:/data" "$IMAGE" "$@"; }

if [[ ! -s "${OSRM}.edges" || "$PBF" -nt "${OSRM}.edges" ]]; then
  echo "==> osrm-extract (the slow stage; several minutes)"
  run osrm-extract -p /opt/car.lua /data/region.osm.pbf
else
  echo "==> extract stage up to date"
fi

echo "==> osrm-partition"
run osrm-partition /data/region.osrm

echo "==> osrm-customize"
run osrm-customize /data/region.osrm

echo
echo "Done. Bring the router up with:  docker compose up -d osrm"
