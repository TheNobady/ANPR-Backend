package com.anpr.backend.camera;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the active camera set from {@code camera-sets/<CAMERA_SET>.json} at startup.
 *
 * <p>The camera set is deployment data, not schema, so it lives in a resource file rather
 * than a migration: porting the system to another city is then a matter of adding a JSON
 * file and setting {@code CAMERA_SET}, with no SQL to edit and no database to wipe.
 *
 * <p>Idempotent by construction -- every row is an upsert keyed on {@code camera_id}, so
 * restarting neither duplicates nor clobbers. Cameras that are not in the active set are
 * deactivated rather than deleted, because {@code camera_edges} and {@code observations}
 * hold foreign keys into this table; a delete would either fail or take real history with
 * it. Everything downstream filters on {@code is_active}.
 */
@Component
// Must run before every other runner: the graph generator and the sanity check both
// iterate over the active camera set, so the set has to be loaded first. An
// ApplicationReadyEvent listener would fire after them.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CameraSetLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CameraSetLoader.class);

    private static final String UPSERT = """
            INSERT INTO cameras (camera_id, name, lat, lon, heading_degrees, zone,
                                 is_provisional, is_active)
            VALUES (?, ?, ?, ?, ?, ?, false, true)
            ON CONFLICT (camera_id) DO UPDATE SET
                name            = EXCLUDED.name,
                lat             = EXCLUDED.lat,
                lon             = EXCLUDED.lon,
                heading_degrees = EXCLUDED.heading_degrees,
                zone            = EXCLUDED.zone,
                is_provisional  = false,
                is_active       = true
            """;

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final String cameraSet;

    public CameraSetLoader(ObjectMapper objectMapper,
                           JdbcTemplate jdbcTemplate,
                           @Value("${CAMERA_SET:chandigarh}") String cameraSet) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.cameraSet = cameraSet;
    }

    /** The active set's name, as the map bootstrap reports it to the frontend. */
    public String activeSetName() {
        return cameraSet;
    }

    @Override
    public void run(ApplicationArguments args) {
        load(cameraSet);
    }

    @Transactional
    public void load(String setName) {
        String path = "camera-sets/%s.json".formatted(setName);
        CameraSetDefinition definition = read(path, setName);

        List<CameraSetDefinition.CameraDefinition> cameras = definition.cameras();
        if (cameras == null || cameras.isEmpty()) {
            throw new IllegalStateException("camera set %s defines no cameras".formatted(path));
        }

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < cameras.size(); i++) {
            CameraSetDefinition.CameraDefinition camera = cameras.get(i);
            camera.validate(i, path);
            if (!ids.add(camera.cameraId())) {
                throw new IllegalStateException(
                        "camera set %s: duplicate camera_id %s".formatted(path, camera.cameraId()));
            }
        }

        for (CameraSetDefinition.CameraDefinition c : cameras) {
            jdbcTemplate.update(UPSERT, c.cameraId(), c.name(), c.lat(), c.lon(),
                    c.headingDegrees(), c.zone());
        }

        // Cameras from another city (or a retired site) stay in the table for their
        // history but drop out of the graph, the simulator and the map.
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        int deactivated = jdbcTemplate.update(
                "UPDATE cameras SET is_active = false WHERE is_active = true "
                        + "AND camera_id NOT IN (" + placeholders + ")",
                ids.toArray());

        log.info("camera set '{}' ({}): {} cameras upserted, {} other cameras deactivated",
                setName, definition.city(), cameras.size(), deactivated);
    }

    private CameraSetDefinition read(String path, String setName) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "camera set '%s' not found: expected %s on the classpath. "
                            .formatted(setName, path)
                            + "Set CAMERA_SET to a file that exists in src/main/resources/camera-sets/");
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, CameraSetDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "camera set %s could not be parsed: %s".formatted(path, e.getMessage()), e);
        }
    }
}
