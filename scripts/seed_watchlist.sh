#!/usr/bin/env bash
#
# Seed the watchlist against the camera set that is actually loaded.
#
# The blacklist and restricted zones that ship in the migrations name
# Chandigarh plates and Sector_17. Point the system at another camera
# set and none of them match anything, so the alert feed stays empty --
# correctly, but unhelpfully for a demo.
#
# This picks plates the simulator has genuinely emitted (the
# best-tracked ones, so each produces a run of sightings and shows the
# repeat-count suppression working) and restricts the busiest zones.
#
#   ./scripts/seed_watchlist.sh [base_url] [plates] [zones]
#
# Run it AFTER a backfill and BEFORE the identity rebuild: the rebuild
# regenerates every alert, so the history picks these up too.

set -euo pipefail

BASE="${1:-http://localhost:8000}"
PLATES="${2:-8}"
ZONES="${3:-3}"

echo "==> blacklisting the $PLATES best-tracked plates"
curl -sf "$BASE/api/vehicles/search?plate=&limit=1" >/dev/null 2>&1 || true

mapfile -t TOP < <(
  docker exec anpr-postgres psql -U anpr -d anpr -tAc \
    "SELECT canonical_plate FROM vehicles
      WHERE canonical_plate IS NOT NULL
      ORDER BY sighting_count DESC, canonical_plate
      LIMIT $PLATES;"
)

REASONS=("Stolen vehicle report" "Outstanding challan" "Person of interest"
         "Unregistered commercial use" "Insurance lapsed")

i=0
for plate in "${TOP[@]}"; do
  [[ -z "$plate" ]] && continue
  reason="${REASONS[$(( i % ${#REASONS[@]} ))]} REF/$(printf '%05d' $((RANDOM % 99999)))"
  severity=$([[ $((i % 3)) -eq 0 ]] && echo high || echo medium)
  curl -sf -X POST "$BASE/api/blacklist" -H 'Content-Type: application/json' \
    -d "{\"plate\":\"$plate\",\"reason\":\"$reason\",\"severity\":\"$severity\"}" >/dev/null \
    && echo "    + $plate ($severity)"
  i=$((i + 1))
done

echo "==> restricting the $ZONES busiest zones to light vehicles"
mapfile -t BUSY < <(
  docker exec anpr-postgres psql -U anpr -d anpr -tAc \
    "SELECT zone FROM cameras WHERE is_active AND zone IS NOT NULL
      GROUP BY zone ORDER BY count(*) DESC, zone LIMIT $ZONES;"
)

for zone in "${BUSY[@]}"; do
  [[ -z "$zone" ]] && continue
  curl -sf -X POST "$BASE/api/restricted-zones" -H 'Content-Type: application/json' \
    -d "{\"zone\":\"$zone\",\"vehicle_types\":[\"truck\",\"bus\"],
         \"reason\":\"Heavy vehicles barred from the $zone commercial core\"}" >/dev/null \
    && echo "    + $zone (truck, bus)"
done

echo
echo "Now run POST /api/identity/rebuild so the history picks these up."
