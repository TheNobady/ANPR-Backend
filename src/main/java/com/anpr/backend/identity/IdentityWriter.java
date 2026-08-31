package com.anpr.backend.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a {@link ResolutionDecision}. Everything that writes lives here, so
 * {@link IdentityResolver} can stay a pure function.
 */
@Component
public class IdentityWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final IdentityProperties properties;

    public IdentityWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                          IdentityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public long applyLink(ObservationFacts observation, ResolutionDecision.Link link) {
        Integer nextSequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(sequence_number), 0) + 1 FROM trajectory_events WHERE vehicle_id = ?",
                Integer.class, link.vehicleId());

        jdbcTemplate.update("""
                INSERT INTO trajectory_events
                    (vehicle_id, observation_id, sequence_number, link_confidence,
                     score_breakdown, from_camera_id, edge_id)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (observation_id) DO NOTHING
                """,
                link.vehicleId(), observation.observationId(), nextSequence,
                (float) link.linkConfidence(), toJson(link.breakdown()),
                link.fromCameraId(), link.edgeId());

        // last_seen_at only ever moves forward: a late-arriving out-of-order event must
        // not rewind the gating window for everything that follows.
        jdbcTemplate.update("""
                UPDATE vehicles
                   SET last_camera_id  = CASE WHEN ? >= last_seen_at THEN ? ELSE last_camera_id END,
                       last_seen_at    = GREATEST(last_seen_at, ?),
                       first_seen_at   = LEAST(first_seen_at, ?),
                       sighting_count  = sighting_count + 1,
                       status          = CASE WHEN sighting_count + 1 >= ? THEN 'confirmed' ELSE status END,
                       canonical_plate = COALESCE(canonical_plate, ?),
                       plate_skeleton  = COALESCE(plate_skeleton, ?)
                 WHERE id = ?
                """,
                timestamp(observation), observation.cameraId(),
                timestamp(observation), timestamp(observation),
                properties.getConfirmAtSightings(),
                observation.plateCanonical(), observation.plateSkeleton(),
                link.vehicleId());

        if (link.linkConfidence() >= properties.getCentroidMinConfidence()) {
            recomputeCentroid(link.vehicleId());
        }
        return link.vehicleId();
    }

    @Transactional
    public long applyNewVehicle(ObservationFacts observation) {
        Long vehicleId = jdbcTemplate.queryForObject("""
                INSERT INTO vehicles (canonical_plate, plate_skeleton, status,
                                      first_seen_at, last_seen_at, last_camera_id,
                                      sighting_count, centroid_count)
                VALUES (?, ?, 'provisional', ?, ?, ?, 1, 0)
                RETURNING id
                """, Long.class,
                observation.plateCanonical(), observation.plateSkeleton(),
                timestamp(observation), timestamp(observation), observation.cameraId());

        jdbcTemplate.update("""
                INSERT INTO trajectory_events
                    (vehicle_id, observation_id, sequence_number, link_confidence)
                VALUES (?, ?, 1, NULL)
                ON CONFLICT (observation_id) DO NOTHING
                """, vehicleId, observation.observationId());

        // The first sighting always seeds the centroid; there is nothing to be unsure about yet.
        recomputeCentroid(vehicleId);
        return vehicleId;
    }

    /**
     * The centroid is the mean of the embeddings that earned their way in -- the seed
     * sighting plus every link confident enough to be trusted -- renormalised to unit
     * length.
     *
     * <p>Recomputed from the contributing set rather than folded in incrementally: pgvector
     * 0.8 has no scalar multiply or divide, so an incremental running mean cannot be
     * expressed in SQL, and the contributing set is never more than a handful of rows.
     * The result is identical and cannot drift.
     */
    private void recomputeCentroid(long vehicleId) {
        jdbcTemplate.update("""
                UPDATE vehicles v
                   SET centroid = sub.centroid,
                       centroid_count = sub.n
                  FROM (
                        SELECT l2_normalize(avg(o.embedding)) AS centroid, count(*) AS n
                          FROM trajectory_events te
                          JOIN observations o ON o.id = te.observation_id
                         WHERE te.vehicle_id = ?
                           AND o.embedding IS NOT NULL
                           AND (te.sequence_number = 1 OR te.link_confidence >= ?)
                       ) sub
                 WHERE v.id = ? AND sub.n > 0
                """, vehicleId, (float) properties.getCentroidMinConfidence(), vehicleId);
    }

    private static java.sql.Timestamp timestamp(ObservationFacts observation) {
        return java.sql.Timestamp.from(observation.firstSeenAt());
    }

    private String toJson(ScoreBreakdown breakdown) {
        try {
            return objectMapper.writeValueAsString(breakdown);
        } catch (Exception e) {
            return "{}";
        }
    }
}
