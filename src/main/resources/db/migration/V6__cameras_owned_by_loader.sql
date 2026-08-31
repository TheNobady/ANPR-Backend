-- Camera rows are no longer seeded by a migration. They are owned by
-- CameraSetLoader, which upserts src/main/resources/camera-sets/<CAMERA_SET>.json
-- at startup, so porting to another city needs no SQL change and no volume wipe.
--
-- V2 and V3 are deliberately left in place rather than rewritten: they have already
-- been applied in running databases and editing them would break Flyway's checksums.
-- Their rows are also not cleared here, because camera_edges (V5) and observations
-- both hold foreign keys into cameras -- a DELETE would either fail outright or take
-- the committed Chandigarh graph with it. The loader upserts the same primary keys on
-- every boot, so whatever those migrations seeded is overwritten by the resource file
-- within a second of startup, and cameras outside the active set are deactivated
-- rather than removed.
COMMENT ON TABLE cameras IS
    'Owned by CameraSetLoader from camera-sets/<CAMERA_SET>.json. Do not seed in migrations.';
