package com.anpr.backend.graph.gen;

import com.anpr.backend.camera.Camera;
import com.anpr.backend.camera.CameraService;
import com.anpr.backend.camera.GeoDistance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Offline generator for {@code camera_edges}.
 *
 * <p>Runs only under the {@code graphgen} profile. It asks OSRM for a driving route
 * between every ordered camera pair, keeps the ones that are plausible vehicle
 * movements, and writes them out as {@code V5__seed_camera_edges.sql}. That file is
 * committed and is what actually populates the table -- the running system never
 * talks to a routing service.
 *
 * <pre>
 *   SPRING_PROFILES_ACTIVE=graphgen mvn spring-boot:run
 * </pre>
 */
@Component
@Order(100)   // after CameraSetLoader
@Profile("graphgen")
public class RouteGeneratorRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RouteGeneratorRunner.class);

    /** Beyond this a pair is not a plausible camera-to-camera hop, either as the crow flies or by road. */
    private static final double MAX_DISTANCE_M = 3000.0;

    /** A camera watching one direction must not produce edges for the opposite one. */
    private static final double MAX_HEADING_DEVIATION_DEG = 90.0;

    /**
     * Pause between routing requests. The public demo server is a shared free
     * service and must be treated gently; a local OSRM container has no such
     * constraint, and at 0 ms a fifty-camera graph builds in seconds rather
     * than minutes. Set {@code GRAPHGEN_POLITENESS_MS=0} when OSRM_URL points
     * at your own instance.
     */
    private final Duration politenessDelay;

    private static final int T_MIN_FLOOR_S = 15;
    private static final int T_MAX_CEILING_S = 600;

    /** The committed Chandigarh artefact. Other camera sets get their own file. */
    private static final String DEFAULT_SET = "chandigarh";
    private static final Path DEFAULT_OUTPUT =
            Path.of("src/main/resources/db/migration/V5__seed_camera_edges.sql");

    private final CameraService cameraService;
    private final OsrmClient osrm;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final String cameraSet;

    public RouteGeneratorRunner(CameraService cameraService, ObjectMapper objectMapper,
                                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${CAMERA_SET:chandigarh}") String cameraSet,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${GRAPHGEN_POLITENESS_MS:150}") long politenessMs) {
        this.cameraService = cameraService;
        this.jdbcTemplate = jdbcTemplate;
        this.cameraSet = cameraSet;
        this.politenessDelay = Duration.ofMillis(Math.max(0, politenessMs));
        this.osrm = new OsrmClient(
                System.getenv().getOrDefault("OSRM_URL", "https://router.project-osrm.org"),
                objectMapper);
    }

    @Override
    public void run(String... args) throws Exception {
        List<Camera> cameras = cameraService.findActive().stream()
                .filter(c -> c.getLat() != null && c.getLon() != null)
                .sorted(Comparator.comparing(Camera::getCameraId))
                .toList();
        log.info("graphgen: {} cameras, {} ordered pairs to consider, "
                        + "{} ms between routing requests",
                cameras.size(), cameras.size() * (cameras.size() - 1),
                politenessDelay.toMillis());

        Stats stats = new Stats();
        List<Edge> edges = new ArrayList<>();

        for (Camera from : cameras) {
            for (Camera to : cameras) {
                if (from.getCameraId().equals(to.getCameraId())) {
                    continue;
                }
                stats.considered++;
                generate(from, to, stats).ifPresent(edges::add);
            }
        }

        Path output = outputPath();
        write(edges, output);
        int applied = apply(edges);

        log.info("""
                        graphgen summary
                          pairs considered  : {}
                          edges kept        : {}
                          skipped, distance : {}
                          skipped, heading  : {}
                          failed            : {}
                          camera set        : {}
                          written to        : {}
                          applied to database: {} edges""",
                stats.considered, edges.size(), stats.skippedDistance,
                stats.skippedHeading, stats.failed, cameraSet,
                output.toAbsolutePath(), applied);
    }

    private Optional<Edge> generate(Camera from, Camera to, Stats stats) throws InterruptedException {
        double straightLine = GeoDistance.metresBetween(
                from.getLat(), from.getLon(), to.getLat(), to.getLon());
        if (straightLine > MAX_DISTANCE_M) {
            stats.skippedDistance++;
            return Optional.empty();
        }

        if (!politenessDelay.isZero()) {
            Thread.sleep(politenessDelay.toMillis());
        }
        Optional<OsrmRoute> maybeRoute =
                osrm.route(from.getLat(), from.getLon(), to.getLat(), to.getLon());
        if (maybeRoute.isEmpty()) {
            // A pair we cannot route is one missing edge, not a failed run.
            stats.failed++;
            log.warn("graphgen: no route for {} -> {}", from.getCameraId(), to.getCameraId());
            return Optional.empty();
        }
        OsrmRoute route = maybeRoute.get();

        if (route.distanceM() > MAX_DISTANCE_M) {
            stats.skippedDistance++;
            return Optional.empty();
        }

        if (!headingAgrees(from, route.initialBearing()) || !headingAgrees(to, route.finalBearing())) {
            stats.skippedHeading++;
            return Optional.empty();
        }

        int typical = (int) Math.round(route.durationS());
        int min = Math.max(T_MIN_FLOOR_S, (int) Math.floor(route.durationS() / 2.0));
        int max = Math.min((int) Math.round(route.durationS() * 3.0), T_MAX_CEILING_S);
        // A degenerate window helps nobody downstream.
        max = Math.max(max, min);

        return Optional.of(new Edge(from.getCameraId(), to.getCameraId(),
                route.distanceM(), typical, min, max, route.geometryJson()));
    }

    /**
     * A camera with no recorded heading watches everything; otherwise the movement has to
     * run broadly the way the camera is pointed.
     */
    private boolean headingAgrees(Camera camera, double routeBearing) {
        Integer heading = camera.getHeadingDegrees();
        return heading == null
                || Bearings.difference(heading, routeBearing) <= MAX_HEADING_DEVIATION_DEG;
    }

    /**
     * Chandigarh's edges are the committed V5 migration. Any other camera set writes
     * beside it instead of overwriting it -- regenerating for a new city must not silently
     * destroy the graph the default deployment ships with.
     */
    private Path outputPath() {
        return DEFAULT_SET.equals(cameraSet)
                ? DEFAULT_OUTPUT
                : Path.of("generated/camera-edges-%s.sql".formatted(cameraSet));
    }

    /**
     * Also loads the edges straight into the running database, so porting to a new city is
     * "add a JSON file, set CAMERA_SET, run graphgen" with no migration to edit and no
     * volume to wipe. The SQL file remains the artefact of record for the default set.
     */
    private int apply(List<Edge> edges) {
        // trajectory_events.edge_id points here; clear the references before replacing rows.
        jdbcTemplate.update("""
                UPDATE trajectory_events SET edge_id = NULL
                 WHERE edge_id IN (SELECT e.id FROM camera_edges e
                                    JOIN cameras c ON c.camera_id = e.from_camera_id
                                   WHERE c.is_active)
                """);
        jdbcTemplate.update("""
                DELETE FROM camera_edges e
                 WHERE EXISTS (SELECT 1 FROM cameras c
                                WHERE c.camera_id IN (e.from_camera_id, e.to_camera_id)
                                  AND c.is_active)
                """);
        int applied = 0;
        for (Edge e : edges) {
            applied += jdbcTemplate.update("""
                    INSERT INTO camera_edges
                        (from_camera_id, to_camera_id, road_distance_m,
                         t_typical_s, t_min_s, t_max_s, path_geom)
                    VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))
                    ON CONFLICT (from_camera_id, to_camera_id) DO NOTHING
                    """, e.from(), e.to(), e.roadDistanceM(), e.typicalS(), e.minS(), e.maxS(),
                    e.geometryJson());
        }
        jdbcTemplate.update("""
                UPDATE camera_edges e
                   SET intermediate_cameras = COALESCE((
                           SELECT array_agg(c.camera_id ORDER BY ST_LineLocatePoint(e.path_geom, c.geom))
                             FROM cameras c
                            WHERE c.camera_id NOT IN (e.from_camera_id, e.to_camera_id)
                              AND c.geom IS NOT NULL AND c.is_active
                              AND ST_DWithin(c.geom::geography, e.path_geom::geography, 30)
                       ), '{}')
                """);
        return applied;
    }

    private void write(List<Edge> edges, Path OUTPUT) throws IOException {
        StringBuilder sql = new StringBuilder(edges.size() * 4096);
        sql.append("""
                -- GENERATED FILE -- do not hand-edit.
                --
                -- Produced by RouteGeneratorRunner under the `graphgen` profile:
                --     SPRING_PROFILES_ACTIVE=graphgen mvn spring-boot:run
                --
                -- Every row is one OSRM driving route between a camera pair, kept only when
                -- both cameras' headings agree with the direction the route actually travels.
                -- The running system reads these rows and never calls a routing service.

                """);
        for (Edge e : edges) {
            sql.append("""
                    INSERT INTO camera_edges
                        (from_camera_id, to_camera_id, road_distance_m, t_typical_s, t_min_s, t_max_s, path_geom)
                    VALUES ('%s', '%s', %.1f, %d, %d, %d, ST_SetSRID(ST_GeomFromGeoJSON('%s'), 4326));
                    """.formatted(e.from(), e.to(), e.roadDistanceM(),
                    e.typicalS(), e.minS(), e.maxS(), e.geometryJson().replace("'", "''")));
        }
        sql.append("""

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
                """);

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, sql.toString(), StandardCharsets.UTF_8);
    }

    private record Edge(String from, String to, double roadDistanceM,
                        int typicalS, int minS, int maxS, String geometryJson) {
    }

    private static final class Stats {
        int considered;
        int skippedDistance;
        int skippedHeading;
        int failed;
    }
}
