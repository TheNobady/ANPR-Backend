package com.anpr.backend.camera;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CameraService {

    private static final Logger log = LoggerFactory.getLogger(CameraService.class);

    /** How far a node-reported position may drift from the surveyed position before we complain. */
    public static final double LOCATION_DRIFT_WARN_METRES = 100.0;

    private static final String INSERT_PROVISIONAL = """
            INSERT INTO cameras (camera_id, name, lat, lon, heading_degrees, zone, is_provisional, is_active)
            VALUES (?, ?, ?, ?, NULL, NULL, true, true)
            ON CONFLICT (camera_id) DO NOTHING
            """;

    private static final String TOUCH_LAST_EVENT_AT = """
            UPDATE cameras
               SET last_event_at = ?
             WHERE camera_id = ?
               AND (last_event_at IS NULL OR last_event_at < ?)
            """;

    private final CameraRepository cameraRepository;
    private final JdbcTemplate jdbcTemplate;

    public CameraService(CameraRepository cameraRepository, JdbcTemplate jdbcTemplate) {
        this.cameraRepository = cameraRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public Optional<Camera> find(String cameraId) {
        return cameraRepository.findById(cameraId);
    }

    @Transactional(readOnly = true)
    public List<Camera> findAll() {
        return cameraRepository.findAllByOrderByCameraIdAsc();
    }

    /**
     * Only the cameras in the active set. The graph generator, the sanity check and the
     * simulator all use this: a camera left over from another city has history worth
     * keeping but must not appear in a new deployment's graph.
     */
    @Transactional(readOnly = true)
    public List<Camera> findActive() {
        return cameraRepository.findByActiveTrueOrderByCameraIdAsc();
    }

    @Transactional(readOnly = true)
    public long count() {
        return cameraRepository.count();
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return cameraRepository.countActive();
    }

    /**
     * Registers a camera we have never seen before from the location the node reported.
     * Provisional cameras carry no heading and no zone until an operator surveys them.
     * {@code ON CONFLICT DO NOTHING} keeps concurrent first-events from the same new
     * camera from colliding.
     */
    @Transactional
    public void autoRegister(String cameraId, Double lat, Double lon, String name) {
        log.warn("Auto-registering unknown camera '{}' at ({}, {}) as provisional -- "
                        + "heading and zone must be set by an operator", cameraId, lat, lon);
        jdbcTemplate.update(INSERT_PROVISIONAL, cameraId, name, lat, lon);
    }

    /**
     * Warns when the node's self-reported position disagrees with the surveyed position.
     * The stored camera row is never modified: the surveyed position is authoritative.
     */
    public void warnIfLocationDrifts(Camera camera, double reportedLat, double reportedLon) {
        if (camera.getLat() == null || camera.getLon() == null) {
            return;
        }
        double metres = GeoDistance.metresBetween(
                camera.getLat(), camera.getLon(), reportedLat, reportedLon);
        if (metres > LOCATION_DRIFT_WARN_METRES) {
            log.warn("Camera '{}' reported position ({}, {}) is {}m from its stored position "
                            + "({}, {}); stored position kept",
                    camera.getCameraId(), reportedLat, reportedLon, Math.round(metres),
                    camera.getLat(), camera.getLon());
        }
    }

    /** Advances {@code last_event_at} to the event's canonical time, never backwards. */
    @Transactional
    public void touchLastEventAt(String cameraId, java.time.Instant eventTime) {
        java.sql.Timestamp ts = java.sql.Timestamp.from(eventTime);
        jdbcTemplate.update(TOUCH_LAST_EVENT_AT, ts, cameraId, ts);
    }
}
