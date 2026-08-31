package com.anpr.backend.identity;

import com.anpr.backend.alert.AlertEngine;
import com.anpr.backend.common.FailureCounters;
import com.anpr.backend.stream.EventStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolve, then alert, then stream -- the single place both the live consumer and the batch
 * rebuild go through, so the two cannot drift.
 *
 * <p>Everything after resolution is best-effort: an alerting or streaming failure is logged
 * and swallowed, because neither is allowed to cost an observation its identity.
 */
@Component
public class IdentityPipeline {

    private static final Logger log = LoggerFactory.getLogger(IdentityPipeline.class);

    private final IdentityService identityService;
    private final AlertEngine alertEngine;
    private final EventStream stream;
    private final JdbcTemplate jdbcTemplate;
    private final FailureCounters failures;

    public IdentityPipeline(IdentityService identityService, AlertEngine alertEngine,
                            EventStream stream, JdbcTemplate jdbcTemplate,
                            FailureCounters failures) {
        this.failures = failures;
        this.identityService = identityService;
        this.alertEngine = alertEngine;
        this.stream = stream;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void process(long observationId) {
        IdentityService.Outcome outcome = identityService.process(observationId);
        if (outcome == null) {
            return;
        }
        try {
            alertEngine.evaluate(outcome);
        } catch (Exception e) {
            failures.increment(FailureCounters.IDENTITY_POST_PROCESSING);
            log.error("identity: alerting failed for observation {}", observationId, e);
        }
        try {
            publishSighting(outcome);
        } catch (Exception e) {
            failures.increment(FailureCounters.STREAM_PUSH);
            log.error("identity: streaming failed for observation {}", observationId, e);
        }
    }

    /** The stream itself drops anything outside the live window, so history never reaches a client. */
    private void publishSighting(IdentityService.Outcome outcome) {
        ObservationFacts facts = outcome.facts();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("camera_id", facts.cameraId());
        payload.put("vehicle_id", outcome.vehicleId());
        payload.put("plate", facts.plateCanonical());
        payload.put("timestamp", facts.firstSeenAt());
        payload.put("is_new_vehicle", outcome.isNewVehicle());
        payload.put("link_confidence", outcome.linkConfidence());
        stream.publish("sighting", payload, facts.firstSeenAt());
    }

    /**
     * Alerts are derived from links, so a rebuild has to clear them: left behind, they point
     * at vehicle ids the rebuild is about to reissue to different vehicles.
     */
    public void clearAlerts() {
        jdbcTemplate.update("DELETE FROM alerts");
        jdbcTemplate.update("ALTER SEQUENCE alerts_id_seq RESTART WITH 1");
    }

    /** Diagnostics for the identity stats endpoint. */
    public List<Map<String, Object>> alertCountsByType() {
        return jdbcTemplate.queryForList(
                "SELECT alert_type, count(*) AS n FROM alerts GROUP BY alert_type ORDER BY 1");
    }
}
