package com.anpr.backend.graph.gen;

import com.anpr.backend.camera.Camera;
import com.anpr.backend.camera.CameraService;
import com.anpr.backend.camera.GeoDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sanity check over a generated graph.
 *
 * <p>Runs only under the {@code graphcheck} profile:
 * <pre>
 *   SPRING_PROFILES_ACTIVE=graphcheck mvn spring-boot:run
 * </pre>
 *
 * <p>Everything it reports is a hint that a camera coordinate in V3 is wrong. The fix
 * is to move the camera and regenerate, not to widen the thresholds.
 */
@Component
@Order(100)   // after CameraSetLoader
@Profile("graphcheck")
public class GraphCheckRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphCheckRunner.class);

    /** Above this, OSRM had to route around something -- often because the camera is off-road. */
    private static final double MAX_DETOUR_RATIO = 2.5;

    /** More outbound edges than this and the camera is probably too close to its neighbours. */
    private static final int MAX_OUTBOUND_EDGES = 8;

    private final CameraService cameraService;
    private final JdbcTemplate jdbcTemplate;

    public GraphCheckRunner(CameraService cameraService, JdbcTemplate jdbcTemplate) {
        this.cameraService = cameraService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        List<Camera> cameras = cameraService.findActive();
        Map<String, Camera> byId = new LinkedHashMap<>();
        cameras.forEach(c -> byId.put(c.getCameraId(), c));

        Long edgeCount = jdbcTemplate.queryForObject("SELECT count(*) FROM camera_edges", Long.class);
        log.info("graphcheck: {} cameras, {} edges", cameras.size(), edgeCount);

        int isolated = reportIsolated(cameras);
        int detours = reportDetours(byId);
        int clustered = reportClustered();

        log.info("""
                        graphcheck summary
                          cameras                 : {}
                          edges                   : {}
                          isolated cameras        : {}
                          suspicious detour edges : {} (road distance > {}x straight line)
                          over-connected cameras  : {} (more than {} outbound edges)""",
                cameras.size(), edgeCount, isolated, detours, MAX_DETOUR_RATIO,
                clustered, MAX_OUTBOUND_EDGES);

        if (isolated == 0 && detours == 0 && clustered == 0) {
            log.info("graphcheck: no warnings");
        }
    }

    private int reportIsolated(List<Camera> cameras) {
        List<String> isolated = jdbcTemplate.queryForList("""
                SELECT c.camera_id
                  FROM cameras c
                 WHERE NOT EXISTS (SELECT 1 FROM camera_edges e WHERE e.from_camera_id = c.camera_id)
                 ORDER BY c.camera_id
                """, String.class);
        for (String cameraId : isolated) {
            Camera camera = cameras.stream()
                    .filter(c -> c.getCameraId().equals(cameraId)).findFirst().orElse(null);
            log.warn("ISOLATED  {} ({}) has no outbound edges at ({}, {}) heading {} -- "
                            + "check the coordinate is on a road and the heading matches the carriageway",
                    cameraId, camera == null ? "?" : camera.getName(),
                    camera == null ? "?" : camera.getLat(), camera == null ? "?" : camera.getLon(),
                    camera == null ? "?" : camera.getHeadingDegrees());
        }
        return isolated.size();
    }

    private int reportDetours(Map<String, Camera> byId) {
        List<Map<String, Object>> edges = jdbcTemplate.queryForList(
                "SELECT from_camera_id, to_camera_id, road_distance_m FROM camera_edges "
                        + "ORDER BY from_camera_id, to_camera_id");
        int flagged = 0;
        for (Map<String, Object> row : edges) {
            String from = (String) row.get("from_camera_id");
            String to = (String) row.get("to_camera_id");
            double roadDistance = ((Number) row.get("road_distance_m")).doubleValue();
            Camera a = byId.get(from);
            Camera b = byId.get(to);
            if (a == null || b == null || a.getLat() == null || b.getLat() == null) {
                continue;
            }
            double straightLine = GeoDistance.metresBetween(
                    a.getLat(), a.getLon(), b.getLat(), b.getLon());
            if (straightLine < 1.0) {
                continue;
            }
            double ratio = roadDistance / straightLine;
            if (ratio > MAX_DETOUR_RATIO) {
                flagged++;
                log.warn("DETOUR    {} -> {}: {}m by road vs {}m straight ({}x)",
                        from, to, Math.round(roadDistance), Math.round(straightLine),
                        String.format("%.1f", ratio));
            }
        }
        return flagged;
    }

    private int reportClustered() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT from_camera_id, count(*) AS outbound
                  FROM camera_edges
                 GROUP BY from_camera_id
                HAVING count(*) > ?
                 ORDER BY count(*) DESC, from_camera_id
                """, MAX_OUTBOUND_EDGES);
        for (Map<String, Object> row : rows) {
            log.warn("CLUSTERED {} has {} outbound edges -- cameras are probably too close together",
                    row.get("from_camera_id"), row.get("outbound"));
        }
        return rows.size();
    }
}
