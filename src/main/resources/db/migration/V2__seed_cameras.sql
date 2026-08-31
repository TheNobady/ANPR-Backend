-- Six fixed cameras on Chandigarh sector junctions.
-- Headings are the compass bearing the camera faces (degrees, 0 = north).

INSERT INTO cameras (camera_id, name, lat, lon, heading_degrees, zone, is_provisional, is_active) VALUES
    ('CHD_CAM_01', 'Sector 17/22 Junction - Himalaya Marg',      30.740430, 76.782120, 135, 'CENTRAL_BUSINESS',   false, true),
    ('CHD_CAM_02', 'Tribune Chowk - Sector 30/31',               30.704600, 76.801700,  45, 'EAST_CORRIDOR',      false, true),
    ('CHD_CAM_03', 'PGI Chowk - Sector 11/12',                   30.762500, 76.771000, 270, 'NORTH_INSTITUTIONAL', false, true),
    ('CHD_CAM_04', 'Sector 34/35 Light Point - Dakshin Marg',    30.721200, 76.764500, 225, 'SOUTH_CORRIDOR',     false, true),
    ('CHD_CAM_05', 'ISBT Sector 43 Approach',                    30.711800, 76.745500, 180, 'TRANSIT_HUB',        false, true),
    ('CHD_CAM_06', 'Transport Chowk - Sector 26 Grain Market',   30.731800, 76.802200,  90, 'INDUSTRIAL_EAST',    false, true);
