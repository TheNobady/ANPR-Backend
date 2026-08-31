-- GENERATED FILE -- do not hand-edit.
--
-- Produced by RouteGeneratorRunner under the `graphgen` profile:
--     SPRING_PROFILES_ACTIVE=graphgen mvn spring-boot:run
--
-- Every row is one OSRM driving route between a camera pair, kept only when
-- both cameras' headings agree with the direction the route actually travels.
-- The running system reads these rows and never calls a routing service.

INSERT INTO camera_edges
    (from_camera_id, to_camera_id, road_distance_m, t_typical_s, t_min_s, t_max_s, path_geom)
VALUES ('AGR_CAM_01', 'AGR_CAM_02', 2231.3, 133, 66, 398, ST_SetSRID(ST_GeomFromGeoJSON('{"coordinates":[[78.008904,27.163447],[78.008904,27.163447],[78.008879,27.163258],[78.008412,27.1597],[78.008399,27.159612],[78.008304,27.159626],[78.008319,27.159715],[78.008324,27.159746],[78.008448,27.160682],[78.008833,27.163447],[78.008926,27.164283],[78.008901,27.1643],[78.008883,27.164323],[78.008874,27.164345],[78.008872,27.164368],[78.008876,27.164392],[78.008886,27.164413],[78.008896,27.164427],[78.008908,27.164438],[78.008923,27.164448],[78.008939,27.164455],[78.00897,27.164674],[78.008969,27.164932],[78.008972,27.164987],[78.008979,27.165217],[78.008957,27.165414],[78.008935,27.165535],[78.008919,27.165611],[78.008855,27.165819],[78.008801,27.166026],[78.008772,27.166242],[78.008772,27.166504],[78.00878,27.166658],[78.008796,27.166858],[78.008805,27.166969],[78.008812,27.167076],[78.008821,27.167248],[78.008832,27.167427],[78.008852,27.167545],[78.008884,27.16773],[78.00895,27.168033],[78.008996,27.168286],[78.009033,27.168491],[78.009085,27.168699],[78.009225,27.169309],[78.009346,27.169884],[78.009351,27.169908],[78.009443,27.170205],[78.009482,27.170467],[78.009488,27.170528],[78.00958,27.170578],[78.009772,27.170721],[78.01042,27.171185],[78.010784,27.171454],[78.010916,27.171559],[78.011231,27.17181],[78.011088,27.172026],[78.011054,27.172101],[78.010831,27.172805],[78.010766,27.172998],[78.010744,27.173038],[78.010649,27.173149],[78.010487,27.173339],[78.010252,27.173642],[78.010056,27.173873],[78.009863,27.174065],[78.009824,27.174101],[78.009426,27.174387]],"type":"LineString"}'), 4326));

-- Cameras the route passes on its way, in the order it passes them.
-- 30 m of the path is about one carriageway's width either side.
UPDATE camera_edges e
   SET intermediate_cameras = COALESCE((
           SELECT array_agg(c.camera_id ORDER BY ST_LineLocatePoint(e.path_geom, c.geom))
             FROM cameras c
            WHERE c.camera_id NOT IN (e.from_camera_id, e.to_camera_id)
              AND c.geom IS NOT NULL
              AND ST_DWithin(c.geom::geography, e.path_geom::geography, 30)
       ), '{}');
