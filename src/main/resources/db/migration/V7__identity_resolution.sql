-- Phase 3: identity resolution.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- observations.plate_skeleton
--
-- Every OCR-confusable class collapsed to one representative, so two reads of
-- the same plate that differ only by a confusable glyph land on the same key.
-- This is the cheap join column; the scorer still does the careful comparison.
-- ---------------------------------------------------------------------------
ALTER TABLE observations ADD COLUMN IF NOT EXISTS plate_skeleton TEXT;

UPDATE observations
   SET plate_skeleton = translate(plate_canonical, 'OIBSZG', '018526')
 WHERE plate_canonical IS NOT NULL AND plate_skeleton IS NULL;

CREATE INDEX IF NOT EXISTS idx_observations_plate_skeleton ON observations (plate_skeleton);
CREATE INDEX IF NOT EXISTS idx_observations_plate_canonical_trgm
    ON observations USING GIN (plate_canonical gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- vehicles
-- ---------------------------------------------------------------------------
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS plate_skeleton TEXT;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS centroid       vector(512);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS centroid_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS sighting_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE vehicles ALTER COLUMN status SET DEFAULT 'provisional';
ALTER TABLE vehicles DROP CONSTRAINT IF EXISTS chk_vehicles_status;
ALTER TABLE vehicles ADD CONSTRAINT chk_vehicles_status
    CHECK (status IN ('provisional', 'confirmed'));

CREATE INDEX IF NOT EXISTS idx_vehicles_plate_skeleton ON vehicles (plate_skeleton);
CREATE INDEX IF NOT EXISTS idx_vehicles_canonical_plate_trgm
    ON vehicles USING GIN (canonical_plate gin_trgm_ops);

-- The gating index. Every candidate lookup is one indexed range scan per inbound
-- edge against this; without it resolution degrades to a table scan per observation.
CREATE INDEX IF NOT EXISTS idx_vehicles_last_camera_last_seen
    ON vehicles (last_camera_id, last_seen_at);

-- ---------------------------------------------------------------------------
-- trajectory_events
-- ---------------------------------------------------------------------------
ALTER TABLE trajectory_events ADD COLUMN IF NOT EXISTS score_breakdown JSONB;
ALTER TABLE trajectory_events ADD COLUMN IF NOT EXISTS from_camera_id  TEXT REFERENCES cameras (camera_id);
ALTER TABLE trajectory_events ADD COLUMN IF NOT EXISTS edge_id         BIGINT REFERENCES camera_edges (id);

-- An observation belongs to exactly one vehicle.
ALTER TABLE trajectory_events DROP CONSTRAINT IF EXISTS uq_trajectory_observation;
ALTER TABLE trajectory_events ADD CONSTRAINT uq_trajectory_observation UNIQUE (observation_id);

CREATE INDEX IF NOT EXISTS idx_trajectory_events_vehicle_seq
    ON trajectory_events (vehicle_id, sequence_number);

-- ---------------------------------------------------------------------------
-- sim_ground_truth
--
-- Written by the simulator straight to the database, never carried in the event
-- payload: the payload has to stay byte-identical to the perception contract, and
-- a resolver that could see the answer would not be measuring anything.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_ground_truth (
    event_id      TEXT PRIMARY KEY,
    sim_vehicle_id TEXT NOT NULL,
    true_plate    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sim_ground_truth_vehicle ON sim_ground_truth (sim_vehicle_id);
