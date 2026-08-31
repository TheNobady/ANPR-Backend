#!/usr/bin/env bash
#
# Exercises the ingest endpoint against a running backend.
#
#   ./scripts/send_test_events.sh [base_url]
#
# Covers: a normal event, an event with no plate, a duplicate tracklet,
# an unknown camera (auto-registration), and a malformed payload.

set -uo pipefail

BASE_URL="${1:-http://localhost:8000}"
EVENTS_URL="$BASE_URL/api/events"

# The perception model emits 512 floats. Generate a valid vector once and reuse it.
embedding_512() {
  python3 - <<'PY'
import json, math
print(json.dumps([round(math.sin(i / 7.0) * 0.1, 6) for i in range(512)]))
PY
}
EMBEDDING="$(embedding_512)"

send() {
  local label="$1" payload="$2"
  echo
  echo "=============================================================="
  echo "  $label"
  echo "=============================================================="
  local response status body
  response="$(curl -sS -w $'\n%{http_code}' \
      -X POST "$EVENTS_URL" \
      -H 'Content-Type: application/json' \
      -d "$payload")"
  status="$(printf '%s' "$response" | tail -n1)"
  body="$(printf '%s' "$response" | sed '$d')"
  echo "HTTP $status"
  if command -v jq >/dev/null 2>&1; then
    printf '%s\n' "$body" | jq . 2>/dev/null || printf '%s\n' "$body"
  else
    printf '%s\n' "$body"
  fi
}

get() {
  local label="$1" path="$2"
  echo
  echo "=============================================================="
  echo "  $label   (GET $path)"
  echo "=============================================================="
  if command -v jq >/dev/null 2>&1; then
    curl -sS "$BASE_URL$path" | jq . 2>/dev/null || curl -sS "$BASE_URL$path"
  else
    curl -sS "$BASE_URL$path"
    echo
  fi
}

# --------------------------------------------------------------------------
# 1. A normal, complete event from a seeded camera.
# --------------------------------------------------------------------------
send "1. Normal event -- CHD_CAM_01, full plate and embedding" "$(cat <<JSON
{
  "event_id": "evt_normal_001",
  "camera_id": "CHD_CAM_01",
  "camera_location": { "lat": 30.740430, "lon": 76.782120, "name": "Sector 17/22 Junction" },
  "timestamp_first_seen": "2026-08-28T14:32:01.123Z",
  "timestamp_last_seen": "2026-08-28T14:32:04.897Z",
  "tracklet_id": "CHD_CAM_01_trk_00231",
  "plate": {
    "text": "PB 65 AB-1234",
    "confidence": 0.94,
    "char_confidences": [0.99, 0.97, 0.91, 0.88, 0.96, 0.93, 0.99, 0.90, 0.92, 0.95],
    "raw_ocr_reads": ["PB65AB1234", "PB6SAB1234", "PB65AB1234"]
  },
  "vehicle": {
    "type": "car", "type_confidence": 0.91,
    "colour": "white", "colour_confidence": 0.87,
    "embedding": $EMBEDDING,
    "bbox_last": [412, 220, 610, 390]
  },
  "detection_confidence": 0.97,
  "node_mode": "live",
  "snapshot_url": "https://snapshots.local/evt_normal_001.jpg"
}
JSON
)"

# --------------------------------------------------------------------------
# 2. No plate at all. The node still reports it -- the embedding is the point.
# --------------------------------------------------------------------------
send "2. Null plate -- embedding only" "$(cat <<JSON
{
  "event_id": "evt_noplate_002",
  "camera_id": "CHD_CAM_02",
  "camera_location": { "lat": 30.704600, "lon": 76.801700, "name": "Tribune Chowk" },
  "timestamp_first_seen": "2026-08-28T14:35:10.000Z",
  "timestamp_last_seen": "2026-08-28T14:35:12.400Z",
  "tracklet_id": "CHD_CAM_02_trk_00088",
  "plate": null,
  "vehicle": {
    "type": "truck", "type_confidence": 0.83,
    "colour": "blue", "colour_confidence": 0.71,
    "embedding": $EMBEDDING,
    "bbox_last": [100, 140, 480, 520]
  },
  "detection_confidence": 0.88,
  "node_mode": "live",
  "snapshot_url": null
}
JSON
)"

# --------------------------------------------------------------------------
# 3. The same (camera_id, tracklet_id) again -- must succeed without a second row.
# --------------------------------------------------------------------------
send "3. Duplicate tracklet -- idempotent, expect 'received' again" "$(cat <<JSON
{
  "event_id": "evt_normal_001_retry",
  "camera_id": "CHD_CAM_01",
  "camera_location": { "lat": 30.740430, "lon": 76.782120, "name": "Sector 17/22 Junction" },
  "timestamp_first_seen": "2026-08-28T14:32:01.123Z",
  "timestamp_last_seen": "2026-08-28T14:32:04.897Z",
  "tracklet_id": "CHD_CAM_01_trk_00231",
  "plate": { "text": "PB65AB1234", "confidence": 0.94, "char_confidences": null, "raw_ocr_reads": null },
  "vehicle": null,
  "detection_confidence": 0.97,
  "node_mode": "live",
  "snapshot_url": null
}
JSON
)"

# --------------------------------------------------------------------------
# 4. A camera we have never heard of -- auto-registered as provisional.
# --------------------------------------------------------------------------
send "4. Unknown camera -- auto-register CHD_CAM_99 as provisional" "$(cat <<JSON
{
  "event_id": "evt_newcam_004",
  "camera_id": "CHD_CAM_99",
  "camera_location": { "lat": 30.750100, "lon": 76.788900, "name": "Sector 9 Rotary (mobile node)" },
  "timestamp_first_seen": "2026-08-28T14:40:00.000Z",
  "timestamp_last_seen": "2026-08-28T14:40:03.250Z",
  "tracklet_id": "CHD_CAM_99_trk_00001",
  "plate": { "text": "IND HR26DK8337", "confidence": 0.79, "char_confidences": null, "raw_ocr_reads": ["HR26DK8337"] },
  "vehicle": { "type": "car", "type_confidence": 0.77, "colour": "silver", "colour_confidence": 0.62, "embedding": null, "bbox_last": [10, 20, 200, 300] },
  "detection_confidence": 0.81,
  "node_mode": "replay",
  "snapshot_url": null
}
JSON
)"

# --------------------------------------------------------------------------
# 5. Malformed: truncated JSON. Persisted to ingest_rejects, 400 back.
# --------------------------------------------------------------------------
send "5. Malformed JSON -- expect 400 rejected" \
  '{"event_id": "evt_broken_005", "camera_id": "CHD_CAM_03", "timestamp_first_seen": '

# --------------------------------------------------------------------------
# 6. Structurally valid but semantically wrong: a 3-element embedding.
# --------------------------------------------------------------------------
send "6. Embedding dimension mismatch -- expect 400 embedding_dimension_mismatch" "$(cat <<'JSON'
{
  "event_id": "evt_badvec_006",
  "camera_id": "CHD_CAM_03",
  "camera_location": { "lat": 30.762500, "lon": 76.771000, "name": "PGI Chowk" },
  "timestamp_first_seen": "2026-08-28T14:45:00.000Z",
  "timestamp_last_seen": "2026-08-28T14:45:02.000Z",
  "tracklet_id": "CHD_CAM_03_trk_00777",
  "plate": { "text": "CH01AA0001", "confidence": 0.90, "char_confidences": null, "raw_ocr_reads": null },
  "vehicle": { "type": "car", "type_confidence": 0.9, "colour": "black", "colour_confidence": 0.8,
               "embedding": [0.021, -0.114, 0.5], "bbox_last": null },
  "detection_confidence": 0.9,
  "node_mode": "live",
  "snapshot_url": null
}
JSON
)"

# --------------------------------------------------------------------------
# 7. last_seen before first_seen.
# --------------------------------------------------------------------------
send "7. Reversed timestamps -- expect 400 negative_dwell" "$(cat <<'JSON'
{
  "event_id": "evt_reversed_007",
  "camera_id": "CHD_CAM_04",
  "camera_location": { "lat": 30.721200, "lon": 76.764500, "name": "Sector 34/35" },
  "timestamp_first_seen": "2026-08-28T14:50:05.000Z",
  "timestamp_last_seen": "2026-08-28T14:50:01.000Z",
  "tracklet_id": "CHD_CAM_04_trk_00012",
  "plate": null, "vehicle": null,
  "detection_confidence": 0.6, "node_mode": "live", "snapshot_url": null
}
JSON
)"

# --------------------------------------------------------------------------
# 8. Known camera reporting a position ~2km from its surveyed one.
#    Accepted; the drift is logged and the cameras row is left alone.
# --------------------------------------------------------------------------
send "8. Location drift on a known camera -- accepted, warning logged" "$(cat <<'JSON'
{
  "event_id": "evt_drift_008",
  "camera_id": "CHD_CAM_05",
  "camera_location": { "lat": 30.730000, "lon": 76.760000, "name": "ISBT 43 (bad GPS fix)" },
  "timestamp_first_seen": "2026-08-28T14:55:00.000Z",
  "timestamp_last_seen": "2026-08-28T14:55:01.500Z",
  "tracklet_id": "CHD_CAM_05_trk_00500",
  "plate": { "text": "ch-01 zz 9999", "confidence": 0.66, "char_confidences": null, "raw_ocr_reads": null },
  "vehicle": null,
  "detection_confidence": 0.7, "node_mode": "live", "snapshot_url": null
}
JSON
)"

# --------------------------------------------------------------------------
# What the system looks like afterwards.
# --------------------------------------------------------------------------
get "System health"      "/api/health/system"
get "Recent events"      "/api/events/recent?limit=10"
get "Recent rejects"     "/api/events/rejects?limit=10"
get "Cameras (GeoJSON)"  "/api/cameras"

echo
echo "Done."
