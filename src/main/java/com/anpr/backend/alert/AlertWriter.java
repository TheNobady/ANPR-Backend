package com.anpr.backend.alert;

import com.anpr.backend.common.FailureCounters;
import com.anpr.backend.stream.EventStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Persists alerts, collapsing repeats, and pushes the live ones onto the stream.
 */
@Component
public class AlertWriter {

    private static final Logger log = LoggerFactory.getLogger(AlertWriter.class);

    /**
     * Collapse a repeat into the existing row rather than adding another.
     *
     * <p>{@code occurred_at} moves forward to the latest sighting so the feed sorts on when
     * the vehicle was last seen, not when it was first noticed.
     */
    private static final String SUPPRESS = """
            UPDATE alerts
               SET repeat_count = repeat_count + 1,
                   occurred_at  = GREATEST(occurred_at, ?::timestamptz),
                   camera_id    = ?,
                   observation_id = ?
             WHERE id = (
                 SELECT id FROM alerts
                  WHERE alert_type = ?
                    AND vehicle_id IS NOT DISTINCT FROM ?
                    -- casts are load-bearing: an uncast parameter here is inferred as an
                    -- interval, and the whole expression fails to type
                    AND occurred_at >= ?::timestamptz - make_interval(secs => ?::double precision)
                    AND occurred_at <= ?::timestamptz
                  ORDER BY occurred_at DESC
                  LIMIT 1)
            RETURNING id
            """;

    private static final String INSERT = """
            INSERT INTO alerts (alert_type, severity, vehicle_id, related_vehicle_id,
                                observation_id, camera_id, occurred_at, message, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AlertProperties properties;
    private final EventStream stream;
    private final FailureCounters failures;

    public AlertWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                       AlertProperties properties, EventStream stream,
                       FailureCounters failures) {
        this.failures = failures;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stream = stream;
    }

    void write(NewAlert alert) {
        try {
            Timestamp occurredAt = Timestamp.from(alert.occurredAt());

            List<Long> suppressed = jdbcTemplate.queryForList(SUPPRESS, Long.class,
                    occurredAt, alert.cameraId(), alert.observationId(),
                    alert.alertType(), alert.vehicleId(),
                    occurredAt, properties.getSuppressionWindowSeconds(), occurredAt);
            if (!suppressed.isEmpty()) {
                log.debug("alert: {} for vehicle {} suppressed into alert {}",
                        alert.alertType(), alert.vehicleId(), suppressed.get(0));
                return;
            }

            Long id = jdbcTemplate.queryForObject(INSERT, Long.class,
                    alert.alertType(), alert.severity().name(), alert.vehicleId(),
                    alert.relatedVehicleId(), alert.observationId(), alert.cameraId(),
                    occurredAt, alert.message(), toJson(alert.metadata()));

            log.info("alert [{}] {} vehicle={} camera={}: {}",
                    alert.severity(), alert.alertType(), alert.vehicleId(),
                    alert.cameraId(), alert.message());
            publish(id, alert);
        } catch (Exception e) {
            // Alerting is downstream of resolution and must never break it -- but a swallowed
            // failure has to be countable, or a broken query here looks exactly like a quiet
            // day. See FailureCounters.
            failures.increment(FailureCounters.ALERT_WRITE);
            log.error("alert: failed to record {} for vehicle {} -- alert lost",
                    alert.alertType(), alert.vehicleId(), e);
        }
    }

    /** Carries the camera's name and position so the frontend can pin it without a second call. */
    private void publish(Long alertId, NewAlert alert) {
        if (alertId == null) {
            return;
        }
        try {
            Map<String, Object> camera = alert.cameraId() == null ? Map.of()
                    : jdbcTemplate.queryForMap(
                            "SELECT name, lat, lon FROM cameras WHERE camera_id = ?", alert.cameraId());

            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("alert_id", alertId);
            payload.put("alert_type", alert.alertType());
            payload.put("severity", alert.severity().name());
            payload.put("vehicle_id", alert.vehicleId());
            payload.put("related_vehicle_id", alert.relatedVehicleId());
            payload.put("camera_id", alert.cameraId());
            payload.put("camera_name", camera.get("name"));
            payload.put("lat", camera.get("lat"));
            payload.put("lon", camera.get("lon"));
            payload.put("occurred_at", alert.occurredAt());
            payload.put("message", alert.message());
            payload.put("metadata", alert.metadata());
            stream.publish("alert", payload, alert.occurredAt());
        } catch (Exception e) {
            failures.increment(FailureCounters.STREAM_PUSH);
            log.error("alert: could not publish {} to the stream", alertId, e);
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            return "{}";
        }
    }
}
