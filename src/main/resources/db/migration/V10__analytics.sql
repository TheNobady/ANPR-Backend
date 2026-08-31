-- Phase 6: analytics rollups.
--
-- The dashboard reads these tables and nothing else. Query endpoints never touch
-- observations or trajectory_events, so response time stays flat as history grows
-- instead of degrading with every hour of traffic.
--
-- Every table is keyed on its time bucket, so a rollup is an upsert into a known
-- key and re-running it repairs rather than duplicates -- which matters because
-- every simulator backfill and every identity rebuild invalidates history.

-- ---------------------------------------------------------------------------
-- agg_camera_density -- per camera, per 5-minute bucket
-- ---------------------------------------------------------------------------
CREATE TABLE agg_camera_density (
    camera_id           TEXT        NOT NULL REFERENCES cameras (camera_id),
    bucket_start        TIMESTAMPTZ NOT NULL,
    bucket_minutes      INTEGER     NOT NULL,
    observation_count   INTEGER     NOT NULL DEFAULT 0,
    unique_vehicle_count INTEGER    NOT NULL DEFAULT 0,
    -- Congestion signal that needs no identity linking at all, so it still means
    -- something for vehicles resolution never matched.
    mean_dwell_ms       INTEGER,
    type_counts         JSONB       NOT NULL DEFAULT '{}',
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (camera_id, bucket_start, bucket_minutes)
);

CREATE INDEX idx_agg_density_bucket ON agg_camera_density (bucket_start DESC, bucket_minutes);

-- ---------------------------------------------------------------------------
-- agg_edge_flow -- per edge, per 15-minute bucket
-- ---------------------------------------------------------------------------
CREATE TABLE agg_edge_flow (
    edge_id             BIGINT      NOT NULL REFERENCES camera_edges (id) ON DELETE CASCADE,
    from_camera_id      TEXT        NOT NULL,
    to_camera_id        TEXT        NOT NULL,
    bucket_start        TIMESTAMPTZ NOT NULL,
    bucket_minutes      INTEGER     NOT NULL,
    traversal_count     INTEGER     NOT NULL DEFAULT 0,
    -- Median, not mean: one vehicle that stopped for a coffee destroys a mean.
    -- Null when sample_count is below analytics.min-samples -- "not enough data"
    -- is more useful than a confident figure computed from three cars.
    median_duration_s   INTEGER,
    p85_duration_s      INTEGER,
    free_flow_s         INTEGER     NOT NULL,
    congestion_ratio    REAL,
    congestion_band     TEXT,
    sample_count        INTEGER     NOT NULL DEFAULT 0,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edge_id, bucket_start, bucket_minutes),
    CONSTRAINT chk_congestion_band CHECK (
        congestion_band IS NULL OR congestion_band IN ('free', 'moderate', 'heavy', 'severe'))
);

CREATE INDEX idx_agg_edge_bucket     ON agg_edge_flow (bucket_start DESC, bucket_minutes);
CREATE INDEX idx_agg_edge_congestion ON agg_edge_flow (congestion_ratio DESC NULLS LAST);

-- ---------------------------------------------------------------------------
-- agg_od_matrix -- zone to zone, per hour
-- ---------------------------------------------------------------------------
CREATE TABLE agg_od_matrix (
    origin_zone         TEXT        NOT NULL,
    destination_zone    TEXT        NOT NULL,
    bucket_start        TIMESTAMPTZ NOT NULL,
    bucket_minutes      INTEGER     NOT NULL,
    vehicle_count       INTEGER     NOT NULL DEFAULT 0,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (origin_zone, destination_zone, bucket_start, bucket_minutes)
);

CREATE INDEX idx_agg_od_bucket ON agg_od_matrix (bucket_start DESC, bucket_minutes);

-- ---------------------------------------------------------------------------
-- agg_od_coverage
--
-- The OD matrix only counts vehicles seen at two or more cameras, which biases it.
-- The denominator is stored alongside so the endpoint can report that bias instead
-- of hiding it; a stated limitation is a credibility gain.
-- ---------------------------------------------------------------------------
CREATE TABLE agg_od_coverage (
    bucket_start            TIMESTAMPTZ NOT NULL,
    bucket_minutes          INTEGER     NOT NULL,
    vehicles_observed       INTEGER     NOT NULL DEFAULT 0,
    vehicles_with_both_ends INTEGER     NOT NULL DEFAULT 0,
    computed_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_start, bucket_minutes)
);
