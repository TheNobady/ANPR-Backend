package com.anpr.backend.alert;

import com.anpr.backend.camera.GeoDistance;
import com.anpr.backend.common.FailureCounters;
import com.anpr.backend.identity.IdentityService;
import com.anpr.backend.identity.ObservationFacts;
import com.anpr.backend.identity.RejectedCandidate;
import com.anpr.backend.identity.RejectionReason;
import com.anpr.backend.identity.ResolutionDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raises alerts from the identity-resolution outcome.
 *
 * <p>Runs on the same single-threaded consumer, immediately after the link decision, and
 * never on the ingest request path. The blacklist and zone checks are in-memory map lookups
 * so the hot loop does not gain a database round trip per observation.
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final JdbcTemplate jdbcTemplate;
    private final WatchlistCache watchlist;
    private final AlertWriter writer;
    private final AlertProperties properties;
    private final FailureCounters failures;

    public AlertEngine(JdbcTemplate jdbcTemplate, WatchlistCache watchlist,
                       AlertWriter writer, AlertProperties properties,
                       FailureCounters failures) {
        this.failures = failures;
        this.jdbcTemplate = jdbcTemplate;
        this.watchlist = watchlist;
        this.writer = writer;
        this.properties = properties;
    }

    public void evaluate(IdentityService.Outcome outcome) {
        try {
            ObservationFacts facts = outcome.facts();
            ObservationDetail detail = detailFor(facts.observationId());
            if (detail == null) {
                return;
            }
            blacklistMatch(outcome, facts, detail);
            restrictedZoneEntry(outcome, facts, detail);
            impossibleSpeed(outcome, facts, detail);
            plateAppearanceMismatch(outcome, facts, detail);
        } catch (Exception e) {
            failures.increment(FailureCounters.ALERT_EVALUATION);
            log.error("alert: evaluation failed for observation {} -- alerts lost for it",
                    outcome.facts().observationId(), e);
        }
    }

    // ------------------------------------------------------------------ blacklist

    private void blacklistMatch(IdentityService.Outcome outcome, ObservationFacts facts,
                                ObservationDetail detail) {
        WatchlistCache.Match match = watchlist.lookup(facts.plateCanonical(), facts.plateSkeleton());
        if (match == null) {
            return;
        }
        boolean exact = "exact".equals(match.matchType());
        Severity base = Severity.parse(match.entry().severity(), Severity.high);
        // A skeleton hit is a plausible read of the wanted plate, not a confirmed one, so it
        // is reported a level lower and labelled -- an operator acting on it deserves to know.
        Severity severity = exact ? base : base.downgraded();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("match_type", match.matchType());
        metadata.put("blacklist_id", match.entry().id());
        metadata.put("blacklist_plate", match.entry().plateCanonical());
        metadata.put("blacklist_reason", match.entry().reason());
        metadata.put("observed_plate", detail.plateText());
        metadata.put("observed_plate_canonical", facts.plateCanonical());
        metadata.put("plate_confidence", detail.plateConfidence());

        String message = exact
                ? "Blacklisted plate %s seen at %s (%s)".formatted(
                        match.entry().plateCanonical(), detail.cameraName(), match.entry().reason())
                : "Possible blacklisted plate: read as %s, matches %s on a fuzzy (skeleton) comparison at %s (%s)"
                        .formatted(facts.plateCanonical(), match.entry().plateCanonical(),
                                detail.cameraName(), match.entry().reason());

        writer.write(new NewAlert(AlertType.BLACKLIST_MATCH, severity, outcome.vehicleId(), null,
                facts.observationId(), facts.cameraId(), facts.firstSeenAt(), message, metadata));
    }

    // -------------------------------------------------------------- restricted zone

    private void restrictedZoneEntry(IdentityService.Outcome outcome, ObservationFacts facts,
                                     ObservationDetail detail) {
        List<RestrictedZone> zones = watchlist.zonesFor(detail.zone());
        if (zones.isEmpty()) {
            return;
        }
        int hour = facts.firstSeenAt().atZone(ZoneOffset.UTC).getHour();
        for (RestrictedZone zone : zones) {
            if (!zone.coversType(detail.vehicleType()) || !zone.coversHour(hour)) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("zone", zone.zone());
            metadata.put("vehicle_type", detail.vehicleType());
            metadata.put("restricted_types", zone.vehicleTypes());
            metadata.put("active_from_hour", zone.activeFromHour());
            metadata.put("active_to_hour", zone.activeToHour());
            metadata.put("observation_hour_utc", hour);
            metadata.put("reason", zone.reason());

            writer.write(new NewAlert(AlertType.RESTRICTED_ZONE_ENTRY, Severity.medium,
                    outcome.vehicleId(), null, facts.observationId(), facts.cameraId(),
                    facts.firstSeenAt(),
                    "%s entered restricted zone %s at %s (%s)".formatted(
                            detail.vehicleType() == null ? "Vehicle" : capitalise(detail.vehicleType()),
                            zone.zone(), detail.cameraName(), zone.reason()),
                    metadata));
        }
    }

    // ------------------------------------------------------------- impossible speed

    /**
     * Two situations, both of which gating cannot already have caught.
     *
     * <p>Gating rejects any edge link whose elapsed time is below the edge's {@code t_min_s},
     * so an accepted edge link is never impossible by construction. What is left is: a new
     * vehicle created while the same plate was somewhere else moments ago, and a
     * fallback-pool link with no edge whose implied speed is absurd.
     */
    private void impossibleSpeed(IdentityService.Outcome outcome, ObservationFacts facts,
                                 ObservationDetail detail) {
        if (outcome.isNewVehicle()) {
            impossibleSpeedForNewVehicle(outcome, facts, detail);
            return;
        }
        if (outcome.result().decision() instanceof ResolutionDecision.Link link
                && link.edgeId() == null) {
            impossibleSpeedForFallbackLink(outcome, facts, detail, link);
        }
    }

    /**
     * The strongest cloned-plate evidence available: a fresh identity was minted for a plate
     * that another vehicle was wearing, elsewhere, more recently than the road allows.
     */
    private void impossibleSpeedForNewVehicle(IdentityService.Outcome outcome,
                                              ObservationFacts facts, ObservationDetail detail) {
        if (facts.plateCanonical() == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT v.id AS other_vehicle_id, v.last_camera_id, v.last_seen_at,
                       EXTRACT(epoch FROM (?::timestamptz - v.last_seen_at)) AS elapsed_seconds,
                       e.t_min_s, e.road_distance_m,
                       oc.lat AS other_lat, oc.lon AS other_lon
                  FROM vehicles v
                  LEFT JOIN camera_edges e
                         ON e.from_camera_id = v.last_camera_id AND e.to_camera_id = ?
                  LEFT JOIN cameras oc ON oc.camera_id = v.last_camera_id
                 WHERE v.canonical_plate = ?
                   AND v.id <> ?
                   AND v.last_seen_at <= ?::timestamptz
                   AND v.last_seen_at >= ?::timestamptz - make_interval(secs => ?)
                 ORDER BY v.last_seen_at DESC
                 LIMIT 1
                """,
                Timestamp.from(facts.firstSeenAt()), facts.cameraId(), facts.plateCanonical(),
                outcome.vehicleId(), Timestamp.from(facts.firstSeenAt()),
                Timestamp.from(facts.firstSeenAt()), properties.getClonePlateWindowSeconds());

        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> other = rows.get(0);
        double elapsed = number(other.get("elapsed_seconds"));
        Integer tMin = other.get("t_min_s") == null ? null : ((Number) other.get("t_min_s")).intValue();

        Double impliedSpeed = impliedSpeedKmh(other, detail, elapsed);

        // Impossible either because the road's own minimum says so, or -- with no edge between
        // the two cameras -- because the straight-line speed is absurd.
        boolean tooFastForEdge = tMin != null && elapsed < tMin;
        boolean tooFastOverGround = tMin == null && impliedSpeed != null
                && impliedSpeed > properties.getImpossibleSpeedKmh();
        if (!tooFastForEdge && !tooFastOverGround) {
            return;
        }

        long otherVehicleId = ((Number) other.get("other_vehicle_id")).longValue();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trigger", "duplicate_plate_new_vehicle");
        metadata.put("plate", facts.plateCanonical());
        metadata.put("from_camera_id", other.get("last_camera_id"));
        metadata.put("to_camera_id", facts.cameraId());
        metadata.put("elapsed_seconds", Math.round(elapsed));
        metadata.put("t_min_s", tMin);
        metadata.put("road_distance_m", other.get("road_distance_m"));
        metadata.put("implied_speed_kmh", impliedSpeed == null ? null : Math.round(impliedSpeed));
        metadata.put("other_vehicle_id", otherVehicleId);

        writer.write(new NewAlert(AlertType.IMPOSSIBLE_SPEED, Severity.high, outcome.vehicleId(),
                otherVehicleId, facts.observationId(), facts.cameraId(), facts.firstSeenAt(),
                "Plate %s seen at %s %ds after being seen at %s -- too fast to be the same vehicle%s"
                        .formatted(facts.plateCanonical(), detail.cameraName(), Math.round(elapsed),
                                cameraName((String) other.get("last_camera_id")),
                                tMin == null ? "" : " (road minimum %ds)".formatted(tMin)),
                metadata));
    }

    /** A fallback link has no edge, so the only check available is speed over the ground. */
    private void impossibleSpeedForFallbackLink(IdentityService.Outcome outcome,
                                                ObservationFacts facts, ObservationDetail detail,
                                                ResolutionDecision.Link link) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.lat AS other_lat, c.lon AS other_lon FROM cameras c WHERE c.camera_id = ?
                """, link.fromCameraId());
        if (rows.isEmpty() || detail.lat() == null) {
            return;
        }
        Map<String, Object> other = rows.get(0);
        double elapsed = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(epoch FROM (?::timestamptz - max(o.first_seen_at)))
                  FROM trajectory_events te JOIN observations o ON o.id = te.observation_id
                 WHERE te.vehicle_id = ? AND o.id <> ?
                """, Double.class, Timestamp.from(facts.firstSeenAt()),
                outcome.vehicleId(), facts.observationId());

        Double speed = impliedSpeedKmh(other, detail, elapsed);
        if (speed == null || speed <= properties.getImpossibleSpeedKmh()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trigger", "fallback_link_no_edge");
        metadata.put("plate", facts.plateCanonical());
        metadata.put("from_camera_id", link.fromCameraId());
        metadata.put("to_camera_id", facts.cameraId());
        metadata.put("elapsed_seconds", Math.round(elapsed));
        metadata.put("t_min_s", null);
        metadata.put("implied_speed_kmh", Math.round(speed));

        writer.write(new NewAlert(AlertType.IMPOSSIBLE_SPEED, Severity.high, outcome.vehicleId(),
                null, facts.observationId(), facts.cameraId(), facts.firstSeenAt(),
                "Vehicle linked from %s to %s implies %d km/h -- no road route and physically implausible"
                        .formatted(cameraName(link.fromCameraId()), detail.cameraName(),
                                Math.round(speed)),
                metadata));
    }

    private Double impliedSpeedKmh(Map<String, Object> other, ObservationDetail detail, double elapsed) {
        Object lat = other.get("other_lat");
        Object lon = other.get("other_lon");
        if (lat == null || lon == null || detail.lat() == null || elapsed <= 0) {
            return null;
        }
        double metres = GeoDistance.metresBetween(
                number(lat), number(lon), detail.lat(), detail.lon());
        return metres / elapsed * 3.6;
    }

    // ------------------------------------------------------ plate/appearance mismatch

    /**
     * The differentiating alert: the plate matched exactly, and the resolver still refused
     * the link because the vehicle did not look the same. That is two physically different
     * vehicles wearing one registration.
     */
    private void plateAppearanceMismatch(IdentityService.Outcome outcome, ObservationFacts facts,
                                         ObservationDetail detail) {
        for (RejectedCandidate rejected : outcome.result().rejected()) {
            if (rejected.rejectionReason() != RejectionReason.appearance_mismatch) {
                continue;
            }
            Map<String, Object> otherVehicle = vehicleSummary(rejected.vehicleId());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("plate", facts.plateCanonical());
            metadata.put("plate_rule", rejected.plateRule());
            metadata.put("cosine_similarity", rejected.cosineSimilarity());
            metadata.put("appearance_score", rejected.appearanceScore());
            metadata.put("total_score", rejected.totalScore());
            metadata.put("elapsed_seconds", Math.round(rejected.elapsedSeconds()));
            metadata.put("this_vehicle", Map.of(
                    "vehicle_id", outcome.vehicleId(),
                    "camera_id", String.valueOf(facts.cameraId()),
                    "type", String.valueOf(detail.vehicleType()),
                    "colour", String.valueOf(detail.vehicleColour())));
            metadata.put("other_vehicle", Map.of(
                    "vehicle_id", rejected.vehicleId(),
                    "camera_id", String.valueOf(rejected.lastCameraId()),
                    "type", String.valueOf(otherVehicle.get("vehicle_type")),
                    "colour", String.valueOf(otherVehicle.get("vehicle_colour"))));

            writer.write(new NewAlert(AlertType.PLATE_APPEARANCE_MISMATCH, Severity.high,
                    outcome.vehicleId(), rejected.vehicleId(), facts.observationId(),
                    facts.cameraId(), facts.firstSeenAt(),
                    ("Plate %s is on two different vehicles: a %s %s at %s and a %s %s at %s. "
                            + "The registrations match exactly but the vehicles do not "
                            + "(appearance similarity %.2f).")
                            .formatted(facts.plateCanonical(),
                                    detail.vehicleColour(), detail.vehicleType(), detail.cameraName(),
                                    otherVehicle.get("vehicle_colour"), otherVehicle.get("vehicle_type"),
                                    cameraName(rejected.lastCameraId()),
                                    rejected.cosineSimilarity() == null ? Double.NaN
                                            : rejected.cosineSimilarity()),
                    metadata));
        }
    }

    /** A camera's operator-facing name, falling back to its id if it has none. */
    private String cameraName(String cameraId) {
        if (cameraId == null) {
            return "an unknown camera";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM cameras WHERE camera_id = ?", String.class, cameraId);
        return names.isEmpty() || names.get(0) == null ? cameraId : names.get(0);
    }

    /** Type and colour of a vehicle's most recent sighting. */
    private Map<String, Object> vehicleSummary(long vehicleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT o.vehicle_type, o.vehicle_colour
                  FROM trajectory_events te JOIN observations o ON o.id = te.observation_id
                 WHERE te.vehicle_id = ?
                 ORDER BY te.sequence_number DESC LIMIT 1
                """, vehicleId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    // --------------------------------------------------------------------- support

    private record ObservationDetail(String plateText, Float plateConfidence, String vehicleType,
                                     String vehicleColour, String cameraName, String zone,
                                     Double lat, Double lon) {
    }

    private ObservationDetail detailFor(long observationId) {
        List<ObservationDetail> rows = jdbcTemplate.query("""
                SELECT o.plate_text, o.plate_confidence, o.vehicle_type, o.vehicle_colour,
                       c.name AS camera_name, c.zone, c.lat, c.lon
                  FROM observations o LEFT JOIN cameras c ON c.camera_id = o.camera_id
                 WHERE o.id = ?
                """, (rs, n) -> {
            float confidence = rs.getFloat("plate_confidence");
            Float plateConfidence = rs.wasNull() ? null : confidence;
            double lat = rs.getDouble("lat");
            Double latitude = rs.wasNull() ? null : lat;
            double lon = rs.getDouble("lon");
            Double longitude = rs.wasNull() ? null : lon;
            return new ObservationDetail(rs.getString("plate_text"), plateConfidence,
                    rs.getString("vehicle_type"), rs.getString("vehicle_colour"),
                    rs.getString("camera_name"), rs.getString("zone"), latitude, longitude);
        }, observationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static double number(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
