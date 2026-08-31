-- Replaces the six-camera bring-up seed with the real 14-camera deployment.
--
-- The cameras sit on Chandigarh's V2/V3 arterial lattice: three parallel
-- NW-SE roads (Udyog Path, Madhya Marg, Vigyan Path) crossed by four NE-SW
-- roads (Jan Marg, Himalaya Marg, Sarovar Path, Sukhna Path). Coordinates are
-- taken from the OpenStreetMap junction nodes and then moved ~110 m back along
-- the approach and ~9 m onto the left-hand carriageway, which is where an ANPR
-- gantry actually stands -- in the middle of an intersection it would read
-- nothing. Every position snaps to the road network within 9 m.
--
-- heading_degrees is the compass direction of the traffic the camera watches:
-- 143 = SE-bound, 323 = NW-bound. CHD_CAM_13 and CHD_CAM_14 share a junction
-- with CHD_CAM_06 and CHD_CAM_03 respectively but watch the opposite
-- carriageway, so both directions of travel are instrumented there.

-- Phase 1 left this behind when the ingest tests hit an unregistered camera.
DELETE FROM observations WHERE camera_id = 'CHD_CAM_99';
DELETE FROM cameras      WHERE camera_id = 'CHD_CAM_99';

INSERT INTO cameras (camera_id, name, lat, lon, heading_degrees, zone, is_provisional, is_active) VALUES
    -- Udyog Path corridor, SE-bound
    ('CHD_CAM_01', 'Sector_22_23_Junction',    30.740613, 76.773625, 143, 'Sector_22', false, true),
    ('CHD_CAM_02', 'Sector_17_21_Junction',    30.734222, 76.779044, 143, 'Sector_17', false, true),
    ('CHD_CAM_03', 'Sector_18_20_Junction',    30.728471, 76.784145, 143, 'Sector_20', false, true),
    ('CHD_CAM_04', 'Sector_19_30_Junction',    30.722783, 76.789385, 143, 'Sector_19', false, true),
    -- Madhya Marg corridor, NW-bound
    ('CHD_CAM_05', 'Sector_9_10_Junction',     30.745849, 76.786264, 323, 'Sector_10', false, true),
    ('CHD_CAM_06', 'Sector_8_18_Junction',     30.739657, 76.791626, 323, 'Sector_18', false, true),
    ('CHD_CAM_07', 'Sector_7_19_Junction',     30.733827, 76.796701, 323, 'Sector_19', false, true),
    ('CHD_CAM_08', 'Sector_19_26_Junction',    30.728181, 76.801972, 323, 'Sector_26', false, true),
    -- Vigyan Path corridor, SE-bound
    ('CHD_CAM_09', 'Sector_3_4_Junction',      30.754514, 76.796236, 143, 'Sector_4',  false, true),
    ('CHD_CAM_10', 'Sector_4_5_Junction',      30.748255, 76.801477, 143, 'Sector_5',  false, true),
    ('CHD_CAM_11', 'Sector_5_6_Junction',      30.742364, 76.806405, 143, 'Sector_6',  false, true),
    ('CHD_CAM_12', 'Sector_6_26_Junction',     30.736510, 76.811295, 143, 'Sector_26', false, true),
    -- Opposite carriageway at two junctions already covered above
    ('CHD_CAM_13', 'Sector_8_18_Junction_SE',  30.741296, 76.790333, 143, 'Sector_8',  false, true),
    ('CHD_CAM_14', 'Sector_18_20_Junction_NW', 30.726877, 76.785503, 323, 'Sector_18', false, true)
ON CONFLICT (camera_id) DO UPDATE SET
    name            = EXCLUDED.name,
    lat             = EXCLUDED.lat,
    lon             = EXCLUDED.lon,
    heading_degrees = EXCLUDED.heading_degrees,
    zone            = EXCLUDED.zone,
    is_provisional  = false,
    is_active       = true;
