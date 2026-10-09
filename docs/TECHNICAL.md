# ANPR Trajectory & Traffic Analytics Backend

## What this is

Most cities already have cameras on their main junctions. Individually each one answers a
small question — "which vehicles passed this point?" — and nothing more. This backend joins
those isolated answers together into a picture of how traffic actually moves through a city.

It takes a stream of sightings from automatic number plate recognition cameras. Each sighting
says roughly: *at 14:32:01, camera 7 saw a white car, the plate looked like `CH01AB1234`, and
here is a 512-number fingerprint of what the vehicle looked like.* The backend decides which
of those sightings are the same physical vehicle, even when the plate was misread or not read
at all, and stitches them into journeys across the city.

Out of that come three things. **Trajectories** — where a given vehicle went, along real
roads, with an honest confidence attached to every link in the chain. **Alerts** — a wanted
plate appearing, a lorry in a pedestrianised zone, or the same registration on two vehicles
that cannot both be where they claim to be. And **traffic analytics** — where the congestion
is, which corridors are busiest, and which parts of the city travel to which other parts.

There is also a built-in traffic simulator, because the perception cameras are not built yet
and no small team can film a city's worth of traffic. It posts synthetic events to the same
public endpoint a real camera would, so everything downstream is exercised for real.

## Key features

**One ingestion contract, many sources.** Every camera — real or simulated — posts the same
JSON to one endpoint. Anything that fails validation is stored with its raw body and the
reason, so a misbehaving camera is diagnosable instead of invisible. Ingestion is a fast path
with nothing slow behind it, because the field devices give up after two seconds.

**A road graph derived from the map, not drawn by hand.** The system works out which cameras
are reachable from which by asking a routing engine for real driving routes, and stores the
road geometry and expected travel time for each. This is what lets it say "this vehicle took
four minutes on a stretch that normally takes ninety seconds" instead of just "these two
cameras saw something".

**Identity resolution that uses three signals together.** Plate text, travel time and visual
appearance each contribute to a score. Any one of them alone is unreliable — plates get
misread, timings vary, and appearance models confuse similar cars — but together they are
strong. A candidate is only accepted if it clears a threshold *and* beats the runner-up by a
margin, because a confident wrong link corrupts two journeys while a missed link only
fragments one.

**Identity survives an unreadable plate.** When the plate cannot be read at all — night, rain,
a motorcycle in front — the vehicle is still matched on appearance and timing. In testing at a
30% unreadable-plate rate, every one of 803 such links was recovered correctly. This is the
capability that separates the system from a plate-matching database.

**Cloned-plate detection.** If the same registration appears on two vehicles that do not look
alike, or in two places faster than any road allows, the system says so and names both
vehicles. This falls out of information the resolver already has: *why* it refused to link two
sightings.

**Trajectories with honest geometry and honest confidence.** A journey is returned as map-ready
GeoJSON that follows real roads. Every hop is explicitly marked as inferred, because the road
between two cameras was never observed — only its endpoints. Where the system has no road
geometry it draws a straight line and says so rather than inventing a route, and it flags when
a camera on the route should have seen the vehicle and did not.

**Four alert types with a live feed.** Blacklisted plates, restricted-zone entries, impossible
speeds and plate/appearance mismatches, streamed to connected dashboards as they happen.
Repeat alerts for the same vehicle collapse into one row with a count, so a wanted vehicle
crossing eight cameras does not bury the feed.

**Traffic analytics that state their own bias.** Congestion, density and origin-destination
flows, computed from pre-aggregated tables so query time stays flat as history grows. The
origin-destination matrix reports what fraction of vehicles it could actually use, because a
number without its denominator is not a measurement.

**Portable to another city by configuration.** Cameras live in a JSON file. Point the system at
a different file, regenerate the road graph, and the map, the analytics and the simulator all
move with it. No SQL to edit and no database to wipe.

**A traffic simulator for development and demonstration.** Generates realistic journeys with
configurable plate-reading errors, unreadable-plate rates, appearance noise and congestion,
either in real time or as a block of past history produced in seconds.

## Setup

### Prerequisites

- **Docker** with Compose v2. That is the only hard requirement — everything runs in
  containers, including the database.
- **Internet access on first run**, to pull base images and Maven dependencies. The road-graph
  generator also needs it, but only when you regenerate the graph.
- **JDK 21 and Maven**, only if you want to build outside Docker. See the note below.

### Start it

```bash
git clone <this repo> && cd Backend
docker compose up --build
```

That is the whole thing. It builds a PostgreSQL image with the PostGIS and pgvector
extensions, waits for it to report healthy, applies all database migrations, loads the
Chandigarh camera set, and starts the API on **port 8000**.

First run takes a few minutes while images build. Later runs take about twenty seconds.

### If a port is already in use

Both host ports are overridable. Nothing inside the containers changes:

```bash
DB_PORT=55432 APP_PORT=8001 docker compose up --build
```

### Verify it came up

```bash
curl localhost:8000/actuator/health          # {"status":"UP",...}
curl localhost:8000/api/health/system        # counts, and any swallowed-failure counters
curl localhost:8000/api/cameras | head -c 300   # 14 cameras plus map bbox and zoom
curl localhost:8000/api/graph | head -c 200     # 73 road edges between them
```

If `/api/cameras` returns 14 features and `/api/graph` reports `edge_count: 73`, the system is
healthy and ready for a demo.

### Building locally (optional)

The Docker build pins JDK 21 and needs nothing from you. If you build on your own machine:

```bash
JAVA_HOME=/usr/lib/jvm/java-21 mvn clean package
```

**The `JAVA_HOME` is not optional on a machine with a newer JDK.** Lombok 1.18.34 cannot run
under JDK 25 and fails with a confusing `ExceptionInInitializerError: TypeTag :: UNKNOWN`. If
you see that, you are on the wrong JDK.

### Stop it

```bash
docker compose down        # stop, keep the data
docker compose down -v     # stop and delete the database volume as well
```

## API reference

Everything is JSON over HTTP on port 8000. There is no authentication yet — that is deliberate
and out of scope for this round.

### What the frontend actually calls

A dashboard needs five endpoints. Everything else is for operators, debugging or tooling.

| Endpoint | When | What you do with it |
| --- | --- | --- |
| `GET /api/cameras` | Once, on load | Draw the camera markers, and use `center`, `bbox` and `suggested_zoom` to position the map. Never hardcode a city. |
| `GET /api/analytics/heatmap` | Every 15–30 s, and on timeline scrub | Draw the road network coloured by traffic. Each line has `normalized` (0–1) for colour and `congestion_band` for a legend. |
| `GET /api/stream` | Once, held open | Server-Sent Events. Drop a marker for each `sighting`; raise a toast and pin the map for each `alert` — the alert already carries `lat`/`lon`. |
| `GET /api/alerts` | On load, then on each streamed alert | Populate the alert feed panel. Each row has everything it needs to render. |
| `GET /api/vehicles/{id}/trajectory` | When a user clicks a vehicle | Draw the journey with `L.geoJSON()`. Features alternate point, line, point, line — no sorting needed. |

Two rules the API follows everywhere, so the frontend can rely on them:

- **No presentation values.** No colours, widths or icon names. The server reports what is
  true — a confidence, a band, whether geometry is real road or a straight line — and the
  client decides how that looks.
- **All geography is GeoJSON.** The frontend never does coordinate maths.

### Ingestion

**`POST /api/events`** — where cameras post sightings. The payload is the perception team's
contract, in snake_case, and is never renamed.

Success is `{"status": "received", "event_id": "evt_7f3a9c"}`. A rejection is HTTP 400 with
`{"status": "rejected", "event_id": "...", "reason": "malformed_json"}` and the raw body is
stored for diagnosis. Repeating the same `(camera_id, tracklet_id)` is safe — it returns
success without inserting twice, so a camera retrying after a timeout costs nothing.

**`GET /api/events/recent?limit=&camera_id=`** — the last N sightings, newest first, for
checking that data is arriving. The 512-number embedding is left out.

**`GET /api/events/rejects?limit=`** — recent rejections with their reason and raw body.

### Map and graph

**`GET /api/cameras`** — every active camera as GeoJSON, plus `camera_set`, `bbox`, `center`
and `suggested_zoom`. Switch `CAMERA_SET` and the map moves to the new city on its own.

**`GET /api/graph`** — the road graph: one LineString per directed edge, with road distance,
the expected travel time window, and any cameras the route passes on the way. Paste it into
geojson.io and you get the drivable network.

### Vehicles and trajectories

**`GET /api/vehicles/{id}`** — a vehicle with its ordered sightings, link confidences and the
score breakdown behind each link.

**`GET /api/vehicles/{id}/trajectory?from=&to=`** — the journey as GeoJSON. Features alternate
sighting (Point) and hop (LineString) in order. Summary fields carry total distance, duration
and the minimum and mean link confidence. `from`/`to` clip the range; a range that clips
everything returns an empty collection with the summary intact, which is an answer rather than
an error. An unknown id is a structured 404.

**`GET /api/trajectory?plate=&from=&to=`** — the same body, found by plate. Resolves through
exact, then skeleton, then fuzzy matching, and tells you which tier hit plus up to five
alternates. No match returns **200 with a null trajectory**, not a 404 — "no vehicle carries
that plate" is a search result.

**`GET /api/vehicles/search?plate=&from=&to=&limit=`** — plate search returning everything a
results panel shows: match type and score, sighting count, first and last camera, status.

### Alerts

**`GET /api/alerts?type=&severity=&from=&to=&vehicle_id=&limit=&offset=`** — the alert feed,
newest first, each row carrying camera name and position, the plate, and `repeat_count`.

**`GET /api/alerts/{id}`** — full context: the sighting that triggered it, the vehicle, and for
a clone alert both vehicles side by side.

**`GET /api/alerts/stats?from=&to=`** — counts by type and severity for a dashboard strip.

**`GET /api/stream`** — Server-Sent Events. Two event types:

```
event: sighting
data: {"camera_id":"CHD_CAM_07","vehicle_id":4471,"plate":"CH01AB1234",
       "timestamp":"...","is_new_vehicle":false,"link_confidence":0.91}

event: alert
data: {"alert_id":1204,"alert_type":"blacklist_match","severity":"high","vehicle_id":4471,
       "related_vehicle_id":null,"camera_id":"CHD_CAM_07","camera_name":"...",
       "lat":30.74,"lon":76.78,"occurred_at":"...","message":"...","metadata":{...}}
```

Only genuinely live events are sent — anything older than 60 seconds is stored but never
streamed, so replaying two hours of history does not flood a dashboard. A comment frame every
20 seconds keeps idle connections open through proxies.

**`GET /api/stream/status`** — open connections, events pushed, events withheld as historic.

### Analytics

All five take optional `from` and `to` and default to the last 15 minutes. That default is why
live mode and timeline scrubbing are the same code path: "live" is the frontend calling with no
range on a timer.

**`GET /api/analytics/heatmap?from=&to=`** — the road network as GeoJSON, weighted by traffic.
Each feature carries `weight`, `normalized` (0–1 against the busiest edge in this response),
median travel time, free-flow time, `congestion_ratio`, `congestion_band` and `sample_count`.
The normalisation is included so the frontend can colour the first feature without scanning the
collection, and `sample_count` lets it grey out edges too sparse to trust.

**`GET /api/analytics/congestion?from=&to=&limit=`** — worst-congested edges first, with both
camera names. Edges with too few samples to rank are excluded and counted, not silently
dropped.

**`GET /api/analytics/density?from=&to=&camera_id=&bucket_minutes=`** — observations, unique
vehicles and mean dwell time per camera over time. Without `camera_id` you get one series per
camera plus a city-wide total.

**`GET /api/analytics/od?from=&to=`** — origin-destination flows between zones, with a
mandatory `coverage` block reporting what fraction of observed vehicles could be used.

**`GET /api/analytics/summary?from=&to=`** — headline numbers: total observations, unique
vehicles, cameras reporting, alerts by severity, busiest camera, worst edge, mean congestion.

**`POST /api/analytics/recompute?from=&to=`** — rebuild the aggregates for a range. Needed after
any backfill; a rebuild does it automatically.

### Admin

No authentication yet.

**`GET|POST /api/blacklist`**, **`DELETE /api/blacklist/{id}`** — the watchlist. Post a plate,
reason and severity; the system canonicalises it and derives its skeleton form for you. A new
entry is in force for the **next** sighting, no restart. Deletion is a soft delete.

**`GET|POST /api/restricted-zones`**, **`DELETE /api/restricted-zones/{id}`** — zone rules: a
zone name, optional vehicle types, optional active hours.

### Simulator — profile `sim` only

These exist only when the `sim` profile is active. In a production build the beans are absent
and every route below returns 404.

**`POST /api/sim/backfill`** — `{"hours": 2, "vehiclesPerHour": 300, "ocrErrorRate": 0.15,
"nullPlateRate": 0.05, "embeddingNoiseSigma": 0.05, "congestionFactor": 1.0}`. Generates that
much history, timestamped in the past, as fast as ingestion accepts it. Returns when done.

**`POST /api/sim/start`** — `{"vehiclesPerMinute": 20, "durationMinutes": 0, ...}` plus the same
degradation knobs. Returns immediately; traffic then flows in real time. Calling it again while
running updates the rates without a restart.

**`POST /api/sim/stop`**, **`GET /api/sim/status`** — stop, and inspect counters and current
rates.

**`POST /api/sim/scenario`** — scripted vehicles on top of the random background. See
[Writing demo scenarios](#writing-demo-scenarios).

### Operations

**`GET /api/health/system`** — camera, observation and reject counts, last ingest time, and the
counters for failures that were caught and swallowed. If `alert_write_failures` is climbing,
alerts are being lost and the log has the exception.

**`GET /api/identity/stats`** — vehicles by status, mean sightings, links, unlinked
observations, and live-queue health.

**`POST /api/identity/rebuild`** — re-resolve every observation from scratch in time order, then
recompute analytics for the range it touched. Reports both.

**`GET /api/identity/evaluate`** — accuracy against simulator ground truth: link precision and
recall, fragmentation, identity purity, and the same figures split by whether the plate was
readable.

**`GET /actuator/health`** — liveness, used by the container healthcheck.

## Running a demo

The whole sequence, in order. It takes about three minutes end to end.

### 1. Start the stack

```bash
docker compose up --build -d
# wait for health
until curl -sf localhost:8000/actuator/health >/dev/null; do sleep 2; done
```

### 2. Fill in some history

Two hours of traffic, produced in about eight seconds, timestamped in the past. The
congestion factor spreads slowdowns unevenly across corridors so the heatmap has something
to show:

```bash
curl -X POST localhost:8000/api/sim/backfill \
  -H 'Content-Type: application/json' \
  -d '{"hours": 2, "congestionFactor": 2.6}'
```

### 3. Resolve identities and build the analytics

```bash
curl -X POST localhost:8000/api/identity/rebuild
```

This re-resolves every observation in time order and then recomputes the analytics
aggregates for the range it touched, reporting both. You do **not** need a separate
recompute call.

Check it worked:

```bash
curl 'localhost:8000/api/identity/evaluate' | jq '.link_precision, .fragmentation'
curl 'localhost:8000/api/analytics/summary' | jq
```

### 4. Watch the live feed

In its own terminal, and leave it running:

```bash
curl -N localhost:8000/api/stream
```

### 5. Start live traffic

```bash
curl -X POST localhost:8000/api/sim/start \
  -H 'Content-Type: application/json' \
  -d '{"vehiclesPerMinute": 30, "ocrErrorRate": 0, "nullPlateRate": 0}'
```

Sighting events start appearing in the stream terminal within seconds. Setting the two
degradation rates to zero makes the scripted set-pieces below deterministic; leave them at
their defaults if you want realistic noise.

### 6. Load the scripted scenario

```bash
curl -X POST localhost:8000/api/sim/scenario \
  -H 'Content-Type: application/json' \
  --data-binary @scenarios/demo.json
```

### What to show, in what order

1. **The map.** `GET /api/cameras` places it; `GET /api/graph` draws the road network. Point
   out that the routes follow real Chandigarh roads and nothing about the city is hardcoded.
2. **The heatmap.** `GET /api/analytics/heatmap` — corridors coloured by congestion, with a
   legend from `congestion_band`. Mention that thinly-sampled edges are greyed out rather
   than coloured confidently.
3. **A journey.** Pick a vehicle with five or more sightings and call
   `/api/vehicles/{id}/trajectory`. The line follows roads; each hop carries a confidence.
4. **Identity recovery.** Find `CH01ZZ9999` from the scenario. It is read cleanly at two
   cameras, its plate is unreadable at the third, and it is read again at the fourth — and it
   is still one vehicle. That link was made on appearance and timing alone.
5. **The alert feed.** The blacklisted lorry `CH01AB1234` crosses five cameras and produces
   **one** alert with `repeat_count: 5`, not five alerts.
6. **The clone pair.** `CH04CL0NE` appears at two cameras 3.6 km apart, fifteen seconds apart,
   with no road between them. Two alerts fire: `impossible_speed` (about 860 km/h implied) and
   `plate_appearance_mismatch`, each naming the other vehicle.
7. **Live blacklisting.** Add a plate the simulator is currently emitting and watch the alert
   arrive on the stream at that vehicle's next camera, with no restart:

   ```bash
   curl -X POST localhost:8000/api/blacklist -H 'Content-Type: application/json' \
     -d '{"plate":"PB34RD2239","reason":"Added live during demo","severity":"high"}'
   ```

### A note on map tiles

Map tiles come from the internet. Venue wifi is unreliable and a blank grey map is a bad look.
If the venue is at all doubtful, cache a tile pack for the demo bounding box beforehand and
point the frontend at a local tile server. The backend needs no internet at all once the road
graph has been generated — that is the only step that calls out.

## Creating a demo for a new city

Everything about the city lives in one JSON file and one generated migration. The procedure
below is the one that works; the placement step in particular was arrived at the hard way and
is worth following exactly.

### 1. Pull the arterial road network from OpenStreetMap

Query Overpass for the target bounding box:

```
[out:json][timeout:120];
way["highway"~"^(trunk|primary|secondary)$"](<south>,<west>,<north>,<east>);
out body geom;
```

### 2. Find the junctions

A junction is an **OSM node shared by two or more named arterial ways**. Match on node ids,
not on coordinate proximity — shared ids are exact. Then collapse nodes within about 120 m of
each other into one junction, so a dual carriageway or a slip road does not read as three
separate crossings.

Pick junctions 0.8–1.5 km apart that form corridors. A vehicle crossing the city should pass
four to six cameras; a scattered cloud of cameras produces a graph with no journeys in it.

### 3. Place each camera — the part that matters

For a camera watching traffic travelling in direction `d`:

- move it **110 m back along the approach** (bearing `d + 180°` from the junction), and
- **9 m to the left** (bearing `d − 90°`, for left-hand traffic),
- set `heading_degrees = d`.

Check with OSRM's `nearest` service that each result snaps to the road network within about
10 m.

**Why this matters.** The graph generator keeps an edge only if the origin camera's heading is
within 90° of the route's initial bearing and the destination camera's heading is within 90° of
its final bearing. It reads those bearings from the first and last two coordinates of the route
geometry. OSRM always snaps the requested point onto the road network and the returned geometry
*starts at the snapped point* — so if the camera sits off the carriageway, the first segment is
a short connector from the camera to the nearest lane, pointing in whatever direction the
coordinate happened to fall. The heading filter then compares the camera's heading against
noise.

The setback puts the camera on the mainline instead of in the junction, where it would snap to
a slip lane. The lateral offset puts it on the correct carriageway of a divided road. With both
applied, measured departure bearings match assigned headings to within 6°.

**What happens if you skip it.** Our first attempt placed all 14 Chandigarh cameras on
intersection centroids:

| Placement | Edges | Isolated cameras |
| --- | --- | --- |
| Intersection centroids | **18** | **8** |
| 110 m setback + 9 m carriageway offset | **73** | **0** |

The tell is that every route out of a camera reports the *same* departure bearing regardless of
destination — that is the snap stub, not the road.

### 4. Write the camera set file

`src/main/resources/camera-sets/<city>.json`:

```json
{
  "city": "Agra",
  "cameras": [
    {
      "camera_id": "AGR_CAM_01",
      "name": "Rambagh_Crossing",
      "lat": 27.163447,
      "lon": 78.008904,
      "heading_degrees": 359,
      "zone": "Rambagh",
      "junction": "Mahatma Gandhi Road",
      "watches": "N-bound traffic"
    }
  ]
}
```

`junction` and `watches` are free-text notes for whoever maintains the file; nothing reads
them. `camera-sets/agra.json` is a worked three-camera example.

### 5. Generate the road graph

```bash
CAMERA_SET=agra docker compose up -d          # load the new cameras
JAVA_HOME=/usr/lib/jvm/java-21 CAMERA_SET=agra SERVER_PORT=8099 \
  DB_URL=jdbc:postgresql://localhost:5432/anpr \
  SPRING_PROFILES_ACTIVE=graphgen mvn spring-boot:run
```

It asks OSRM for a route between every ordered camera pair, 150 ms apart, and keeps a pair only
when both distances are under 3 km and both headings agree with the direction of travel. It
writes the SQL alongside and loads the edges straight into the database, so there is no
migration to edit and no volume to wipe.

Correct output looks like:

```
graphgen summary
  pairs considered  : 6
  edges kept        : 1
  skipped, distance : 2
  skipped, heading  : 3
  failed            : 0
  camera set        : agra
  applied to database: 1 edges
```

Some pairs skipped by heading is right — that is the direction filter refusing to link
opposing traffic. All of them skipped means the placement is wrong; go back to step 3.

### 6. Sanity-check it

```bash
JAVA_HOME=/usr/lib/jvm/java-21 CAMERA_SET=agra SERVER_PORT=8099 \
  DB_URL=jdbc:postgresql://localhost:5432/anpr \
  SPRING_PROFILES_ACTIVE=graphcheck mvn spring-boot:run
```

**Isolated cameras (no outbound edges) are a real problem** — the coordinate or the heading is
wrong. Fix and regenerate.

**Detour warnings are usually not.** An edge whose road distance exceeds 2.5× the straight line
is flagged, and most of them are genuine U-turns rather than mistakes:

- *Same-junction twins.* Two cameras on opposite carriageways sit ~220 m apart but need ~1.8 km
  to drive between. Expected.
- *Corridor backtracks.* Travelling against a corridor's direction requires going round.
  Expected.

Chandigarh's committed graph reports 12 such warnings and none of them are errors.

### 7. Update the scenario and restart

Point `scenarios/demo.json` at camera ids that exist in the new city, then:

```bash
CAMERA_SET=agra docker compose up -d
curl localhost:8000/api/cameras | jq '.camera_set, .center, .suggested_zoom'
```

The map centre and zoom move on their own. Cameras from the previous city are deactivated, not
deleted — they keep their history but drop out of the graph, the map and the simulator.

## Writing demo scenarios

A scenario is a JSON file of scripted vehicles that run **on top of** the random background
traffic, so a rehearsed moment happens in a realistic crowd rather than in an empty city.

```bash
curl -X POST localhost:8000/api/sim/scenario \
  -H 'Content-Type: application/json' --data-binary @scenarios/demo.json
```

### Fields

| Field | Meaning |
| --- | --- |
| `plate` | The vehicle's true registration. Two vehicles may share one — that is how you build a clone. |
| `type` | `car`, `motorcycle`, `truck` or `bus`. Matters for restricted-zone rules. |
| `colour` | Reported colour. Make clone pairs differ, so the alert reads clearly. |
| `route` | Camera ids in order, at least two. Legs should follow real edges; a leg with no edge still runs, using a 120 s default, and the response warns you. |
| `startDelaySeconds` | Seconds after the request before the first sighting. This is how you control timing between vehicles. |
| `forceNullPlateAt` | Camera ids where the plate is dropped deterministically, whatever the random rate says. |
| `embeddingSeed` | Names the vehicle's appearance. Two vehicles with different seeds look reliably different; the same seed always reproduces the same vehicle. |

Anything with a leading underscore (`_comment`) is ignored, so you can annotate the file.

### Set-piece 1 — identity recovered without a plate

```json
{
  "plate": "CH01ZZ9999", "type": "car", "colour": "white",
  "route": ["CHD_CAM_09", "CHD_CAM_10", "CHD_CAM_11", "CHD_CAM_12"],
  "startDelaySeconds": 30,
  "forceNullPlateAt": ["CHD_CAM_11"]
}
```

Read cleanly at the first two cameras, plate dropped entirely at the third, read again at the
fourth. Resolution has to carry it across the gap on appearance and timing alone. Without
`forceNullPlateAt` you would be hoping the random 5% rate lands on the camera you want, which
it will not do on stage.

### Set-piece 2 — a blacklisted vehicle, and suppression

```json
{
  "plate": "CH01AB1234", "type": "truck", "colour": "white",
  "route": ["CHD_CAM_06", "CHD_CAM_03", "CHD_CAM_01", "CHD_CAM_02", "CHD_CAM_04"],
  "startDelaySeconds": 15,
  "forceNullPlateAt": []
}
```

`CH01AB1234` is seeded into the blacklist as a stolen-vehicle report. Five cameras means five
matches, which collapse into **one** alert with `repeat_count: 5`. It is a lorry and
`CHD_CAM_02` is in Sector 17, so it also trips the restricted-zone rule — one vehicle
demonstrating two alert types and the suppression logic.

### Set-piece 3 — a clone pair

```json
{
  "plate": "CH04CL0NE", "type": "car", "colour": "white",
  "embeddingSeed": "clone-pair-original",
  "route": ["CHD_CAM_09", "CHD_CAM_10"], "startDelaySeconds": 20
},
{
  "plate": "CH04CL0NE", "type": "car", "colour": "black",
  "embeddingSeed": "clone-pair-impostor",
  "route": ["CHD_CAM_04", "CHD_CAM_03"], "startDelaySeconds": 35
}
```

Same registration, different colours, different `embeddingSeed` so they genuinely do not look
alike. `CHD_CAM_09` and `CHD_CAM_04` are 3.6 km apart with no road edge between them, and the
two are seen fifteen seconds apart — about 860 km/h over the ground. The second sighting raises
both `impossible_speed` and `plate_appearance_mismatch`, each naming the other vehicle.

The `embeddingSeed` is what makes this reliable. Without it the two vehicles get random
appearance vectors that are *usually* dissimilar — fine for a test, useless for a rehearsal
that has to work in front of an audience.

### Determinism

For a rehearsed run, turn the random degradation off first:

```bash
curl -X POST localhost:8000/api/sim/start -H 'Content-Type: application/json' \
  -d '{"vehiclesPerMinute": 20, "ocrErrorRate": 0, "nullPlateRate": 0}'
```

Otherwise a corrupted or dropped plate read can cost one repeat on the suppression count —
realistic, but it makes a rehearsed number wobble.

## Configuration reference

### Environment variables

| Variable | Default | What it does |
| --- | --- | --- |
| `CAMERA_SET` | `chandigarh` | Which `camera-sets/<name>.json` to load at startup. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/anpr` | Database URL. Compose sets this for the container. |
| `DB_USER` / `DB_PASSWORD` | `anpr` / `anpr` | Database credentials. |
| `SERVER_PORT` | `8000` | Port the API listens on inside the container. |
| `DB_PORT` | `5432` | **Host** port for Postgres. Change if 5432 is taken. |
| `APP_PORT` | `8000` | **Host** port for the API. |
| `SPRING_PROFILES` | `sim` | Compose passes this as the active profile. Set to anything else in production and `/api/sim/**` disappears. |
| `LOG_LEVEL` | `INFO` | `DEBUG` adds per-request trajectory timings. |
| `OSRM_URL` | `https://router.project-osrm.org` | Routing server, used only by `graphgen`. |
| `APPEARANCE_STRONG` / `_WEAK` / `_NEUTRAL` | `0.30` / `0.22` / `0.15` | Override the appearance thresholds without a rebuild. |

### `application.yml`

**Identity resolution** (`identity:`) — the scoring that decides which sightings are one vehicle.

| Key | Default | Meaning |
| --- | --- | --- |
| `threshold` | `4.0` | Total score a candidate must reach to be accepted. |
| `ambiguity-margin` | `1.0` | If the runner-up is this close, both are rejected and a new vehicle is created. |
| `fallback-window-seconds` | `900` | How far back the plate-skeleton fallback pool looks. |
| `centroid-min-confidence` | `0.7` | Link confidence needed before a sighting may move the appearance centroid. |
| `confirm-at-sightings` | `2` | Sightings at which a provisional vehicle becomes confirmed. |
| `scoring.plate-exact` / `-skeleton` / `-distance1` / `-distance2` | `6.0` / `4.0` / `2.0` / `0.5` | Plate agreement scores. |
| `scoring.plate-veto-distance` | `2.0` | Beyond this edit distance the candidate is rejected outright. |
| `scoring.travel-weight` / `-sigma` | `2.0` / `0.30` | Travel-time agreement, log-normal around the edge's typical time. |
| `scoring.appearance-strong` / `-weak` / `-neutral` / `-penalty` | `5.0` / `2.0` / `0.0` / `-3.0` | Appearance scores per band. |
| `scoring.appearance-*-threshold` | `0.30` / `0.22` / `0.15` | Cosine similarity band edges. **Model-specific — see below.** |

**Analytics** (`analytics:`)

| Key | Default | Meaning |
| --- | --- | --- |
| `density-bucket-minutes` | `5` | Bucket size for per-camera counts. |
| `edge-bucket-minutes` | `15` | Bucket size for edge flow and congestion. |
| `od-bucket-minutes` | `60` | Bucket size for origin-destination flows. |
| `min-samples` | `5` | Below this many traversals, an edge's duration statistics are written as null. |
| `rollup-buckets` | `3` | How many recent buckets the scheduled job redoes, so late data is absorbed. |
| `rollup-interval-ms` | `60000` | How often that job runs. |
| `default-window-minutes` | `15` | Range used when a query gives none. |
| `band-moderate` / `-heavy` / `-severe` | `1.2` / `1.6` / `2.2` | Congestion ratio band edges. |

**Alerts** (`alerts:`)

| Key | Default | Meaning |
| --- | --- | --- |
| `suppression-window-seconds` | `300` | Repeats of the same alert for the same vehicle inside this window increment a count instead of creating a row. |
| `clone-plate-window-seconds` | `900` | How far back to look for another vehicle wearing the same plate. |
| `impossible-speed-kmh` | `150` | Above this implied speed, a link with no road edge is impossible. |

**Live stream** (`stream:`)

| Key | Default | Meaning |
| --- | --- | --- |
| `live-window-seconds` | `60` | Events older than this are stored but never streamed. |
| `heartbeat-seconds` | `20` | Comment-frame interval, to keep proxies from dropping idle connections. |
| `max-emitters` | `50` | Connections beyond this are refused with 503 rather than accumulated. |

**Query API** (`api:`)

| Key | Default | Meaning |
| --- | --- | --- |
| `detour-ratio` | `1.6` | A hop taking more than this multiple of the edge's typical time is flagged as a suspected detour. |
| `search-limit` / `search-max-limit` | `20` / `200` | Default and hard cap for plate search results. |

### Calibrating the appearance thresholds

**These are a property of your embedding model, not universal constants.** They are cosine
similarities, and what counts as "similar" depends entirely on what produced the vectors.

The shipped values (0.30 / 0.22 / 0.15) are calibrated for the simulator, whose same-vehicle
similarity sits around 0.58 and whose different-vehicle similarity sits around 0.00. A real
re-identification model typically shows 0.85+ for the same vehicle. **If you plug in a real
model and leave these alone, every comparison lands in the penalty band, and the system quietly
stops recovering identity for unreadable plates while precision still reads 1.00** — which is
exactly the kind of failure nobody notices.

Re-measure with ground truth, leave-one-out so a centroid does not include the observation
being scored against it:

```sql
WITH tot AS (
  SELECT g.sim_vehicle_id AS v, sum(o.embedding) AS s, count(*) AS n
    FROM observations o JOIN sim_ground_truth g ON g.event_id = o.event_id
   WHERE o.embedding IS NOT NULL GROUP BY g.sim_vehicle_id)
SELECT round(avg(1 - (o.embedding <=> l2_normalize(t.s - o.embedding)))::numeric, 3) AS same_avg
  FROM observations o JOIN sim_ground_truth g ON g.event_id = o.event_id
  JOIN tot t ON t.v = g.sim_vehicle_id
 WHERE o.embedding IS NOT NULL AND t.n > 1;
```

Run the same query with `t.v <> g.sim_vehicle_id` for the different-vehicle distribution, then
place the thresholds in the gap between them.

## Architecture notes

### From event to answer

```
camera / simulator
      │  HTTP POST /api/events
      ▼
  ingestion ──────────────────► observations          (validate, dedupe, store; rejects kept)
      │  offer id to a queue (non-blocking)
      ▼
  identity consumer  (single thread)
      ├─ gating      ── candidate vehicles from the road graph + a plate fallback pool
      ├─ scoring     ── plate + travel time + appearance
      ├─ assignment  ──► vehicles, trajectory_events
      ├─ alerting    ──► alerts
      └─ streaming   ──► SSE clients (live events only)
      
  rollup job (every 60s) ──► agg_camera_density, agg_edge_flow, agg_od_matrix
      ▼
  query endpoints ── trajectories, search, alerts, analytics
```

Ingestion's only job is to accept the event and get out of the way. Everything expensive
happens behind a queue.

### Profiles

| Profile | What it turns on |
| --- | --- |
| *(none)* | Normal operation: ingestion, identity, alerts, stream, queries, analytics. |
| `sim` | The traffic simulator and `/api/sim/**`. Absent in production — the routes 404. |
| `graphgen` | A one-shot tool that builds the road graph from OSRM and exits. |
| `graphcheck` | A one-shot sanity check over a generated graph. |

The two graph profiles are the only code that ever calls a routing service. In normal
operation the system reads `camera_edges` from the database and talks to nothing external.

### Decisions worth explaining

**Ingestion reads the raw request body, not a bound object.** Once Spring's JSON binding
rejects a request the body is gone — and a malformed payload is exactly the one you most need
to keep. The controller takes a `String` and parses it itself, so anything that fails reaches
the rejects table verbatim.

**Observations are written with native SQL, not the ORM.** Two reasons: the 512-dimension
embedding is a pgvector type the ORM has no mapping for, and idempotency is expressed as
`ON CONFLICT (camera_id, tracklet_id) DO NOTHING`, which makes a retried delivery cost one
statement instead of a read followed by a write.

**The identity consumer is single-threaded, on purpose.** Resolution reads a vehicle's state
and then mutates it. Two threads processing sightings of the same vehicle would race on its
last-seen time and its appearance centroid, and greedy linking is order-dependent anyway. The
queue offer from ingestion is non-blocking and a full queue drops rather than waits, because
identity is downstream of ingestion and must never be able to slow it down.

**`POST /api/identity/rebuild` exists because order matters.** A simulator backfill posts two
hours of history from eight threads at once, so events arrive in essentially arbitrary order,
and greedy linking is only meaningful in time order. The rebuild replays everything in
`first_seen_at` order through the same resolver the live path uses — there is no second copy of
the logic. It is also the development loop: change a threshold, rebuild, re-measure.

**Alerts are muted during a rebuild.** A rebuild regenerates every historical alert. None of
them are live events, and pushing hundreds of them at a connected dashboard would be actively
misleading. The stream is silenced for the duration and alerts are deleted and regenerated with
the links they derive from — left behind, they would point at vehicle ids the rebuild is about
to hand to different vehicles.

**Analytics reads only aggregate tables.** Query cost is bounded by the number of buckets in
the window, not by how much traffic has ever passed through the city. It is also why live mode
and timeline scrubbing are one code path: "live" is the frontend asking for the last fifteen
minutes on a timer. The rollups are idempotent per bucket, which they have to be — every
backfill and every rebuild invalidates history and they get re-run constantly.

**Aggregate queries are bucket-granular and half-open.** A range ending at 05:00 includes
buckets that *start* before 05:00, never the one starting at 05:00. Without that, a query
whose end lands inside a still-filling bucket returns moving numbers and looks like a bug.

**Medians, not means.** One vehicle that stopped for a coffee destroys a mean travel time. And
below `analytics.min-samples` traversals the duration statistics are written as null rather
than computed from three cars — a null that says "not enough data" is more useful than a
confident wrong number, and the API reports how many edges were excluded for that reason.

**The origin-destination matrix reports its own coverage.** It can only count vehicles seen at
two or more cameras, which under-counts short trips and vehicles the resolver never linked.
That denominator ships with every response.

**Swallowed failures are counted.** Alerting must not break identity resolution and streaming
must not break ingestion, so both are wrapped in catch-alls. That is the right trade, but an
invisible failure is worse than a loud one: a broken query in the alert writer once produced
zero alerts for an entire phase and looked exactly like a quiet day. Every such catch now logs
at ERROR and increments a counter on `GET /api/health/system`.

### What is not here

No authentication, no audit logging, no role-based access. `/api/blacklist` and
`/api/restricted-zones` are unauthenticated administrative endpoints. This is a deliberate
scope boundary, not an oversight — do not expose this to an untrusted network as it stands.

## Layout

```
docker/postgres/Dockerfile        PostGIS 16-3.4 + pgvector
Dockerfile                        multi-stage build of the application
docker-compose.yml                postgres + backend, healthchecked and ordered
scenarios/demo.json               scripted vehicles for the demo
src/main/resources/
  camera-sets/                    per-city camera definitions; CAMERA_SET picks one
  db/migration/                   schema and the generated road-graph seed
  application.yml                 all tunables
src/main/java/com/anpr/backend/
  ingest/                         the /api/events contract, validation, reject capture
  camera/                         camera entity, the set loader, map bootstrap
  observation/                    sighting storage and read models
  graph/                          camera_edges and GET /api/graph
  graph/gen/                      OSRM client, graphgen and graphcheck runners
  identity/                       gating, scoring, resolution, rebuild, evaluation
  trajectory/                     one-query trajectory assembly and its GeoJSON
  alert/                          watchlist cache, the four detectors, suppression
  stream/                         the SSE registry and its live-window gate
  analytics/                      rollup jobs and the aggregate query endpoints
  common/                         error handling, health, failure counters
```
