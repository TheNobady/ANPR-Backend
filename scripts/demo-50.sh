#!/usr/bin/env bash
#
# Seed and start the 50-camera tricity demo, from whatever state the
# database is in.
#
#   ./scripts/demo-50.sh [hours] [vehicles-per-hour]
#
# Takes about seven minutes, nearly all of it the identity rebuild.
# Assumes the one-time setup in RUN-DEMO.txt is already done: the OSRM
# data built, the tricity camera set generated, and its road graph
# loaded. It does NOT re-run those -- they only change when the camera
# set does.

set -euo pipefail

cd "$(dirname "$0")/.."

BASE="${BASE_URL:-http://localhost:8000}"
HOURS="${1:-8}"
RATE="${2:-1500}"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
since() { printf '    (%dm %02ds)\n' $(( ($(date +%s) - $1) / 60 )) $(( ($(date +%s) - $1) % 60 )); }

TOTAL=$(date +%s)

step "Checking the stack"
# The routing engine is only needed to regenerate the road graph, and it
# holds well over a gigabyte resident. Leaving it up during a demo run
# has been enough to push this machine into swap and lose the backend
# mid-rebuild, so stop it here.
if docker compose ps --services --filter status=running 2>/dev/null | grep -qx osrm; then
  echo "    stopping osrm (only needed for graphgen, frees ~1.4 GB)"
  docker compose --profile graphgen stop osrm >/dev/null 2>&1 || true
fi

docker compose up -d postgres backend >/dev/null
until curl -sf "$BASE/actuator/health" >/dev/null 2>&1; do sleep 2; done

# Health goes UP before CameraSetLoader has finished upserting, so polling
# it alone can read a half-loaded set -- seen returning 15 of 50 cameras.
# Wait for the count to stop moving instead.
prev=-1
for _ in $(seq 1 60); do
  now=$(curl -s "$BASE/api/cameras" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["features"]))' 2>/dev/null || echo 0)
  [[ "$now" -gt 0 && "$now" == "$prev" ]] && break
  prev=$now
  sleep 1
done

SET=$(curl -s "$BASE/api/cameras" | python3 -c 'import sys,json;print(json.load(sys.stdin)["camera_set"])')
COUNT=$(curl -s "$BASE/api/cameras" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["features"]))')
EDGES=$(curl -s "$BASE/api/graph" | python3 -c 'import sys,json;print(json.load(sys.stdin)["edge_count"])')
echo "    camera set '$SET': $COUNT cameras, $EDGES edges"

# The road graph lives in the database, so a wiped volume takes it with
# it. It is a committed artefact though, so reload it rather than
# re-running graphgen (which would need OSRM and its 223 MB extract).
# The simulator reloads the graph itself at the start of a backfill.
if [[ "$EDGES" == "0" && -f generated/camera-edges-$SET.sql ]]; then
  echo "    no edges in the database — reloading generated/camera-edges-$SET.sql"
  docker exec -i anpr-postgres psql -U anpr -d anpr -q < "generated/camera-edges-$SET.sql"
  EDGES=$(curl -s "$BASE/api/graph" | python3 -c 'import sys,json;print(json.load(sys.stdin)["edge_count"])')
  echo "    $EDGES edges restored"
fi

if [[ "$EDGES" == "0" ]]; then
  echo "    !! no road graph for '$SET'. Regenerate it (see RUN-DEMO.txt)," >&2
  echo "       or restore generated/camera-edges-$SET.sql" >&2
  exit 1
fi

if [[ "$SET" != "tricity" ]]; then
  echo "    !! expected 'tricity'. Set CAMERA_SET=tricity in Backend/.env," >&2
  echo "       then: JAVA_HOME=/usr/lib/jvm/java-21 mvn -q package -DskipTests" >&2
  echo "             docker compose up -d --build backend" >&2
  exit 1
fi

step "Wiping previous data"
# Ordered DELETEs: trajectory_events references observations, so a
# cascade from vehicles would destroy ingested events.
docker exec anpr-postgres psql -U anpr -d anpr -q -c \
 "BEGIN; DELETE FROM agg_edge_flow; DELETE FROM agg_camera_density; DELETE FROM agg_od_matrix;
  DELETE FROM agg_od_coverage; DELETE FROM alerts; DELETE FROM trajectory_events;
  DELETE FROM vehicles; DELETE FROM observations; TRUNCATE sim_ground_truth;
  DELETE FROM blacklist; DELETE FROM restricted_zones; COMMIT;"
echo "    done"

step "Backfilling ${HOURS}h at ${RATE} vehicles/hour"
t=$(date +%s)
curl -s -X POST --max-time 1800 "$BASE/api/sim/backfill" -H 'Content-Type: application/json' \
  -d "{\"hours\":$HOURS,\"vehiclesPerHour\":$RATE,\"congestionFactor\":2.6}" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
if 'vehicles' not in d:
    sys.exit('    backfill failed: ' + json.dumps(d)[:300])
print(f\"    {d['vehicles']:,} vehicles, {d['events_sent']:,} events, {d['events_rejected']} rejected\")"
since $t

step "Seeding the watchlist"
# Before the rebuild: the rebuild regenerates every alert, and that is
# what applies these entries to the backfilled history.
./scripts/seed_watchlist.sh "$BASE" >/dev/null
python3 scripts/generate_scenario.py --out scenarios/tricity.json >/dev/null
echo "    blacklist, restricted zones and scenario ready"

step "Rebuilding identities (the long one)"
t=$(date +%s)
curl -s -X POST --max-time 3600 "$BASE/api/identity/rebuild" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(f\"    {d['observations_processed']:,} observations -> {d['vehicles_created']:,} vehicles, {d['links_made']:,} links ({d['elapsed_ms']/d['observations_processed']:.2f} ms each)\")"
since $t

step "Starting live traffic and the scripted set-pieces"
curl -s -X POST "$BASE/api/sim/start" -H 'Content-Type: application/json' \
  -d '{"vehiclesPerMinute":60,"ocrErrorRate":0,"nullPlateRate":0}' >/dev/null
curl -s -X POST "$BASE/api/sim/scenario" -H 'Content-Type: application/json' \
  --data-binary @scenarios/tricity.json \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('    scenario:',d['vehicles_scheduled'],'vehicles',('· '+'; '.join(d['warnings'])) if d['warnings'] else '')"

step "Ready"
curl -s "$BASE/api/alerts/stats" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print(f\"    {d['total']:,} alerts:\", ', '.join(f'{k} {v}' for k,v in d['by_type'].items()))"
curl -s "$BASE/api/identity/evaluate" | python3 -c "
import sys,json;d=json.load(sys.stdin)
p,r=d['link_precision'],d['link_recall']
print(f\"    link precision {p['rate']:.4f} · recall {r['rate']:.4f} over {r['denominator']:,} links\")" 2>/dev/null || true
since $TOTAL
echo
echo "    Open http://localhost:5173  (start the frontend with:"
echo "    cd ../Frontend/city-intelligence && npm run dev)"
