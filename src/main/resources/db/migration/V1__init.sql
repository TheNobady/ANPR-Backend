-- ANPR trajectory tracking backend -- Phase 1 schema.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- cameras
-- ---------------------------------------------------------------------------
CREATE TABLE cameras (
    camera_id       TEXT PRIMARY KEY,
    name            TEXT,
    lat             DOUBLE PRECISION,
    lon             DOUBLE PRECISION,
    geom            GEOMETRY(Point, 4326)
                        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)) STORED,
    heading_degrees INTEGER,
    zone            TEXT,
    is_provisional  BOOLEAN     NOT NULL DEFAULT false,
    is_active       BOOLEAN     NOT NULL DEFAULT true,
    last_event_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cameras_geom ON cameras USING GIST (geom);

-- ---------------------------------------------------------------------------
-- vehicles -- created for Phase 3 (identity resolution). No Phase 1 code
-- reads or writes this table.
-- ---------------------------------------------------------------------------
CREATE TABLE vehicles (
    id              BIGSERIAL PRIMARY KEY,
    canonical_plate TEXT,
    first_seen_at   TIMESTAMPTZ,
    last_seen_at    TIMESTAMPTZ,
    last_camera_id  TEXT REFERENCES cameras (camera_id),
    status          TEXT        NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicles_canonical_plate ON vehicles (canonical_plate);

-- ---------------------------------------------------------------------------
-- observations -- one row per (camera, tracklet).
-- ---------------------------------------------------------------------------
CREATE TABLE observations (
    id                      BIGSERIAL PRIMARY KEY,
    event_id                TEXT        NOT NULL,
    camera_id               TEXT        NOT NULL REFERENCES cameras (camera_id),
    tracklet_id             TEXT        NOT NULL,
    first_seen_at           TIMESTAMPTZ NOT NULL,
    last_seen_at            TIMESTAMPTZ NOT NULL,
    dwell_ms                INTEGER     NOT NULL,

    plate_text              TEXT,
    plate_confidence        REAL,
    plate_char_confidences  REAL[],
    plate_raw_reads         TEXT[],
    plate_canonical         TEXT,

    vehicle_type            TEXT,
    vehicle_type_confidence REAL,
    vehicle_colour          TEXT,
    vehicle_colour_confidence REAL,

    embedding               vector(512),
    bbox_last               INTEGER[],
    detection_confidence    REAL,

    node_mode               TEXT,
    snapshot_url            TEXT,
    node_reported_lat       DOUBLE PRECISION,
    node_reported_lon       DOUBLE PRECISION,

    -- Populated by Phase 3 identity resolution; always null in Phase 1.
    vehicle_id              BIGINT REFERENCES vehicles (id),

    received_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_observations_camera_tracklet UNIQUE (camera_id, tracklet_id)
);

CREATE INDEX idx_observations_camera_first_seen ON observations (camera_id, first_seen_at);
CREATE INDEX idx_observations_plate_canonical   ON observations (plate_canonical);
CREATE INDEX idx_observations_first_seen        ON observations (first_seen_at);

-- ---------------------------------------------------------------------------
-- trajectory_events -- created for Phase 3. No Phase 1 code touches it.
-- ---------------------------------------------------------------------------
CREATE TABLE trajectory_events (
    id              BIGSERIAL PRIMARY KEY,
    vehicle_id      BIGINT      NOT NULL REFERENCES vehicles (id),
    observation_id  BIGINT      NOT NULL REFERENCES observations (id),
    sequence_number INTEGER     NOT NULL,
    link_confidence REAL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_trajectory_vehicle_sequence UNIQUE (vehicle_id, sequence_number)
);

CREATE INDEX idx_trajectory_events_vehicle ON trajectory_events (vehicle_id, sequence_number);

-- ---------------------------------------------------------------------------
-- ingest_rejects -- every rejected payload is kept with its raw body.
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_rejects (
    id          BIGSERIAL PRIMARY KEY,
    raw_payload JSONB       NOT NULL,
    reason      TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ingest_rejects_received_at ON ingest_rejects (received_at DESC);
