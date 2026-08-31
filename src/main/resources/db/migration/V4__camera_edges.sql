-- Directed road-network edges between cameras.
--
-- Generated offline by the `graphgen` profile against OSRM and committed as
-- V5__seed_camera_edges.sql. The running system only ever reads this table --
-- it never calls a routing service.
CREATE TABLE camera_edges (
    id                   BIGSERIAL PRIMARY KEY,
    from_camera_id       TEXT             NOT NULL REFERENCES cameras (camera_id),
    to_camera_id         TEXT             NOT NULL REFERENCES cameras (camera_id),
    road_distance_m      DOUBLE PRECISION NOT NULL,
    t_typical_s          INTEGER          NOT NULL,
    t_min_s              INTEGER          NOT NULL,
    t_max_s              INTEGER          NOT NULL,
    path_geom            GEOMETRY(LineString, 4326) NOT NULL,
    -- Cameras the route passes on its way, in the order they are passed.
    intermediate_cameras TEXT[]           NOT NULL DEFAULT '{}',
    generated_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_camera_edges_from_to UNIQUE (from_camera_id, to_camera_id)
);

CREATE INDEX idx_camera_edges_path_geom ON camera_edges USING GIST (path_geom);
CREATE INDEX idx_camera_edges_from      ON camera_edges (from_camera_id);
