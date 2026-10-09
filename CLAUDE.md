# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 3.3 / Java 21 backend that reconstructs vehicle trajectories across a city from
ANPR camera sightings, raises alerts, and serves traffic analytics. Postgres 16 with PostGIS
(road geometry) and pgvector (512-d appearance embeddings). `README.md` is the portfolio overview;
`docs/TECHNICAL.md` is the full reference and is thorough — read it for domain concepts, the API surface, and the demo script.
This file covers what you need to *work on* the code.

## Commands

```bash
# Build and test. JAVA_HOME is NOT optional on this machine.
JAVA_HOME=/usr/lib/jvm/java-21 mvn clean package

# A single test class / method
JAVA_HOME=/usr/lib/jvm/java-21 mvn test -Dtest=ConfusionEditDistanceTest
JAVA_HOME=/usr/lib/jvm/java-21 mvn test -Dtest='PlateSkeletonTest#foldsConfusableLettersEvenInsideAnOrdinaryPlate'

# Run the stack (API on port 8000, not 8080)
docker compose up --build -d
DB_PORT=55432 APP_PORT=8001 docker compose up -d   # if host ports are taken

# Rebuild just the app after a code change
JAVA_HOME=/usr/lib/jvm/java-21 mvn -q package -DskipTests && docker compose up -d --build backend

docker compose logs backend --since 5m | grep -iE "error|exception"
docker exec anpr-postgres psql -U anpr -d anpr -c "SELECT ..."
```

**The `JAVA_HOME=/usr/lib/jvm/java-21` prefix is mandatory.** The default JDK here is 25 and
Lombok 1.18.34 dies under it with `ExceptionInInitializerError: TypeTag :: UNKNOWN`. The Docker
build pins 21 so containers are unaffected.

Run Maven from the repo root — a `cd` into `src/...` first is a recurring mistake that produces
"there is no POM in this directory".

### Profiles

| Profile | Effect |
| --- | --- |
| *(none)* | Normal operation. |
| `sim` | Traffic simulator + `/api/sim/**`. Compose sets this by default via `SPRING_PROFILES`. |
| `graphgen` | One-shot: builds the road graph from OSRM, writes SQL, loads edges, exits. |
| `graphcheck` | One-shot: sanity-checks a generated graph. |

The graph profiles are the only code that calls an external routing service. Run them against
a free port so they don't collide with the container:

```bash
JAVA_HOME=/usr/lib/jvm/java-21 CAMERA_SET=agra SERVER_PORT=8099 \
  DB_URL=jdbc:postgresql://localhost:55432/anpr \
  SPRING_PROFILES_ACTIVE=graphgen mvn spring-boot:run
```

### The verification loop

Most changes to resolution, alerting or analytics are only meaningful against data. The cycle:

```bash
# 1. wipe (wait for the identity queue to drain first, or the DELETEs race it)
curl -s localhost:8000/api/identity/stats | jq .live_queue_depth   # want 0
docker exec anpr-postgres psql -U anpr -d anpr -c \
 "BEGIN; DELETE FROM agg_edge_flow; DELETE FROM agg_camera_density; DELETE FROM agg_od_matrix;
  DELETE FROM agg_od_coverage; DELETE FROM alerts; DELETE FROM trajectory_events;
  DELETE FROM vehicles; DELETE FROM observations; TRUNCATE sim_ground_truth; COMMIT;"

# 2. generate, resolve, measure
curl -X POST localhost:8000/api/sim/backfill -H 'Content-Type: application/json' \
  -d '{"hours":2,"congestionFactor":2.6}'
curl -X POST localhost:8000/api/identity/rebuild      # also recomputes analytics
curl localhost:8000/api/identity/evaluate             # precision/recall/fragmentation vs ground truth
```

`POST /api/identity/rebuild` is **required** after a backfill: the simulator posts history from
eight threads at once, and greedy linking only means anything in `first_seen_at` order.

## Architecture

### Pipeline

```
POST /api/events → observations → IdentityQueue (single thread) → IdentityPipeline
                                                                    ├─ IdentityService  (gate → resolve → persist)
                                                                    ├─ AlertEngine      (4 detectors)
                                                                    └─ EventStream      (SSE, live only)
RollupService @Scheduled(60s) → agg_* tables → AnalyticsQueryService
```

Ingestion's only job is to accept and get out of the way; everything expensive is behind the
queue. The queue offer is non-blocking and drops when full — identity must never be able to
slow ingestion.

### Package map

`ingest` (the `/api/events` contract) · `camera` (entity, set loader, map bootstrap) ·
`observation` (storage/read models) · `graph` + `graph/gen` (edges, OSRM tooling) ·
`identity` (gating, scoring, rebuild, evaluation) · `trajectory` (one-query GeoJSON assembly) ·
`alert` (watchlist cache, detectors, suppression) · `stream` (SSE registry) ·
`analytics` (rollups + aggregate queries) · `common` (errors, health, failure counters).

### Invariants worth knowing before you change things

- **`IdentityResolver` is pure.** It takes facts + candidates and returns a decision plus
  rejected candidates; it reads and writes nothing. `IdentityWriter` persists. Keep that seam —
  it is what makes the resolver unit-testable and what clone detection is built on
  (`RejectionReason.appearance_mismatch`).
- **The identity consumer is single-threaded on purpose.** Resolution reads vehicle state then
  mutates it. `IdentityQueue.runExclusively(...)` is the mutex the rebuild takes; without it a
  rebuild's DELETEs race an in-flight `process()` and trip FKs.
- **Live and batch share one path.** Both go through `IdentityPipeline.process`. Never add
  logic to one only.
- **Analytics reads `agg_*` tables only.** Touching `observations` or `trajectory_events` from
  `AnalyticsQueryService` breaks the design (query cost must be bounded by bucket count, not
  history size). Rollups are the only analytics code allowed to read raw data.
- **Aggregate ranges are half-open**: `bucket_start >= snap(from) AND bucket_start < to`. A
  closed upper bound pulls in a still-filling bucket and makes identical queries return moving
  numbers.
- **Ranked queries need a tiebreaker.** `ORDER BY weight DESC` alone returns the same rows in
  different orders between calls, which looks like the data changed.
- **Cameras are owned by `CameraSetLoader`, not migrations.** It reads
  `camera-sets/${CAMERA_SET}.json` and upserts; cameras outside the active set are
  **deactivated, not deleted** (FKs from `camera_edges`/`observations`). It is
  `@Order(HIGHEST_PRECEDENCE)` because `graphgen`/`graphcheck` must see the loaded set.
  Everything downstream filters on `is_active`.
- **Never `TRUNCATE ... CASCADE` from `vehicles`.** `trajectory_events` references
  `observations`; a cascade destroys ingested events. Use ordered DELETEs.
- **Alerts are derived data.** A rebuild deletes and regenerates them, and mutes the SSE stream
  for its duration.

### Database access patterns

JPA is used for simple reads. Native SQL via `JdbcTemplate` is used wherever the ORM can't help:

- `observations.embedding` (`vector(512)`) and `camera_edges.path_geom` are **deliberately
  unmapped** on their entities. Inserts bind `CAST(? AS vector)`; geometry is returned by
  `ST_AsGeoJSON` and spliced into responses as raw JSON (`@JsonRawValue`) — never parsed into
  Java and re-serialised.
- Idempotency is `ON CONFLICT (camera_id, tracklet_id) DO NOTHING ... RETURNING id`.
- Cosine similarity is computed in SQL with pgvector's `<=>` (similarity = `1 - distance`).
  pgvector 0.8 has **no scalar multiply/divide**, so the appearance centroid is recomputed as
  `l2_normalize(avg(embedding))` over contributing sightings rather than folded in incrementally.

### SQL gotchas that have each cost an hour here

- **Bound parameters need explicit casts** in date arithmetic. `? - make_interval(secs => ?)`
  infers the parameter as an `interval` and fails to type; write
  `?::timestamptz - make_interval(secs => ?::double precision)`.
- **`rs.wasNull()` reports on the most recent getter.** Read it immediately after the column's
  getter, not further down an argument list, or you get `0.0` where you meant `null`.
- **Flyway treats `${...}` in SQL as a placeholder**, including inside comments and string
  literals. `${CAMERA_SET}` in a migration fails the whole startup.
- **`%` in a `formatted()` *argument*** is not unescaped again — the trigram operator is a single
  `%` there, not `%%`.

### Failure counters

`AlertEngine`, `AlertWriter`, `IdentityPipeline` and `EventStream` catch everything by design
(alerting must not break resolution; streaming must not break ingestion). Every such catch logs
at ERROR **and** increments `FailureCounters`, surfaced as `failures` on
`GET /api/health/system`. If a feature is silently doing nothing, check there first — a broken
SQL query in the alert writer once produced zero alerts for a whole phase and looked exactly
like a quiet day.

### Calibration is model-specific

`identity.scoring.appearance-*-threshold` are cosine similarities tuned to the **simulator's**
embeddings (same-vehicle ≈ 0.58, different ≈ 0.00). A real re-ID model sits around 0.85+.
Swapping the embedding source without re-measuring silently drops null-plate recovery to zero
while precision still reads 1.00. `docs/TECHNICAL.md`'s "Calibrating the appearance thresholds" section
has the leave-one-out query — leave-one-out matters, a centroid containing the observation being
scored against it flatters itself badly.

## Testing

Only two unit test classes exist, both on the pure string logic everything else depends on:
`PlateSkeletonTest` and `ConfusionEditDistanceTest`. `PlateSkeleton.fold` must stay in step with
the `translate(plate_canonical, 'OIBSZG', '018526')` in `V7__identity_resolution.sql` — a test
pins that. There is no integration test suite; correctness is verified against simulator ground
truth via `GET /api/identity/evaluate`.

## Conventions

- **Records for DTOs, not Lombok.** Lombok is used on JPA entities only.
- **All tunables in `application.yml`** under `identity:`, `analytics:`, `alerts:`, `stream:`,
  `api:`. No magic numbers in scoring, banding or windowing code.
- **No presentation values in responses** — no colours, widths or icon names. The server reports
  what is true (a confidence, a band, `geometry_source`, `inferred`); the client decides how it
  looks.
- **All geography is GeoJSON**, so the frontend does no coordinate maths.
- Migrations are schema + generated seed only. `V5__seed_camera_edges.sql` is machine-written by
  `graphgen` for the `chandigarh` set; other camera sets write to `generated/` instead and load
  straight into the database.
