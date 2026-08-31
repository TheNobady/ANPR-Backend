-- Phase 5: alerts.

-- ---------------------------------------------------------------------------
-- blacklist
--
-- Both plate forms are stored so a lookup is an equality test on an indexed
-- column, never a scan: canonical for a clean read, skeleton so a plate read
-- through a confusable glyph still matches.
-- ---------------------------------------------------------------------------
CREATE TABLE blacklist (
    id              BIGSERIAL PRIMARY KEY,
    plate           TEXT        NOT NULL,   -- as entered by the operator
    plate_canonical TEXT        NOT NULL,
    plate_skeleton  TEXT        NOT NULL,
    reason          TEXT        NOT NULL,
    severity        TEXT        NOT NULL DEFAULT 'high',
    is_active       BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_blacklist_severity CHECK (severity IN ('low', 'medium', 'high'))
);

-- One active entry per plate; a retired entry keeps its row for the audit trail.
CREATE UNIQUE INDEX uq_blacklist_active_plate
    ON blacklist (plate_canonical) WHERE is_active;
CREATE INDEX idx_blacklist_skeleton ON blacklist (plate_skeleton) WHERE is_active;

-- ---------------------------------------------------------------------------
-- restricted_zones
-- ---------------------------------------------------------------------------
CREATE TABLE restricted_zones (
    id                BIGSERIAL PRIMARY KEY,
    zone              TEXT        NOT NULL,
    vehicle_types     TEXT[],                 -- null means every type
    active_from_hour  INTEGER,                -- null means always in force
    active_to_hour    INTEGER,
    reason            TEXT,
    is_active         BOOLEAN     NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_restricted_hours CHECK (
        (active_from_hour IS NULL AND active_to_hour IS NULL)
        OR (active_from_hour BETWEEN 0 AND 23 AND active_to_hour BETWEEN 0 AND 23))
);

CREATE INDEX idx_restricted_zones_zone ON restricted_zones (zone) WHERE is_active;

-- ---------------------------------------------------------------------------
-- alerts
-- ---------------------------------------------------------------------------
CREATE TABLE alerts (
    id                  BIGSERIAL PRIMARY KEY,
    alert_type          TEXT        NOT NULL,
    severity            TEXT        NOT NULL,
    vehicle_id          BIGINT REFERENCES vehicles (id) ON DELETE CASCADE,
    -- the other party in a clone alert
    related_vehicle_id  BIGINT REFERENCES vehicles (id) ON DELETE CASCADE,
    observation_id      BIGINT REFERENCES observations (id) ON DELETE CASCADE,
    camera_id           TEXT REFERENCES cameras (camera_id),
    -- when the vehicle was seen, never when the row was written: an alert replayed
    -- from history must sort into the timeline where it belongs.
    occurred_at         TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    message             TEXT        NOT NULL,
    metadata            JSONB,
    repeat_count        INTEGER     NOT NULL DEFAULT 1,
    CONSTRAINT chk_alerts_severity CHECK (severity IN ('low', 'medium', 'high'))
);

CREATE INDEX idx_alerts_occurred_at      ON alerts (occurred_at DESC);
CREATE INDEX idx_alerts_type_occurred_at ON alerts (alert_type, occurred_at DESC);
CREATE INDEX idx_alerts_vehicle          ON alerts (vehicle_id);
-- Serves the suppression lookup: same type, same vehicle, recent.
CREATE INDEX idx_alerts_suppression      ON alerts (alert_type, vehicle_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Seeds, so the system has something to fire on immediately.
-- ---------------------------------------------------------------------------
INSERT INTO blacklist (plate, plate_canonical, plate_skeleton, reason, severity) VALUES
    ('CH01AB1234', 'CH01AB1234', 'CH01A81234', 'Stolen vehicle report CHD/2026/00418', 'high'),
    ('PB65XY9012', 'PB65XY9012', 'P865XY9012', 'Outstanding challan, registration suspended', 'medium');

INSERT INTO restricted_zones (zone, vehicle_types, active_from_hour, active_to_hour, reason) VALUES
    ('Sector_17', ARRAY['truck', 'bus'], 8, 20,
     'Heavy vehicles barred from the Sector 17 commercial core during trading hours');
