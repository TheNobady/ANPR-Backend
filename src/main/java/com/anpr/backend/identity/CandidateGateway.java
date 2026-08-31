package com.anpr.backend.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gating stage: cheap, recall-first, index-only.
 *
 * <p>Two pools, unioned. The graph pool asks, for every edge that ends at this camera,
 * which vehicles were last seen at the edge's other end within that edge's travel-time
 * window -- one indexed range scan per inbound edge against
 * {@code (last_camera_id, last_seen_at)}, never a table scan. The fallback pool catches
 * vehicles that took a route the graph does not cover, and is kept deliberately narrow:
 * exact skeleton equality inside a short window, never a global embedding search.
 *
 * <p>Cosine similarity is computed here, in SQL, by pgvector. Pulling 512 floats per
 * candidate into Java to compare them would be both slower and pointless.
 */
@Component
public class CandidateGateway {

    /**
     * The observation is joined in rather than passed as parameters so its embedding never
     * leaves the database.
     */
    private static final String SQL = """
            WITH obs AS (
                SELECT id, camera_id, first_seen_at, embedding, plate_skeleton
                  FROM observations
                 WHERE id = ?
            ),
            via_edge AS (
                SELECT v.id                AS vehicle_id,
                       v.canonical_plate,
                       v.plate_skeleton,
                       v.last_camera_id,
                       EXTRACT(epoch FROM (o.first_seen_at - v.last_seen_at)) AS elapsed_seconds,
                       e.id                AS edge_id,
                       e.t_typical_s,
                       CASE WHEN v.centroid IS NOT NULL AND o.embedding IS NOT NULL
                            THEN 1 - (v.centroid <=> o.embedding) END AS cosine_similarity
                  FROM obs o
                  JOIN camera_edges e ON e.to_camera_id = o.camera_id
                  JOIN vehicles v
                    ON v.last_camera_id = e.from_camera_id
                   AND v.last_seen_at >= o.first_seen_at - make_interval(secs => e.t_max_s)
                   AND v.last_seen_at <= o.first_seen_at - make_interval(secs => e.t_min_s)
            ),
            fallback AS (
                SELECT v.id                AS vehicle_id,
                       v.canonical_plate,
                       v.plate_skeleton,
                       v.last_camera_id,
                       EXTRACT(epoch FROM (o.first_seen_at - v.last_seen_at)) AS elapsed_seconds,
                       NULL::bigint        AS edge_id,
                       NULL::integer       AS t_typical_s,
                       CASE WHEN v.centroid IS NOT NULL AND o.embedding IS NOT NULL
                            THEN 1 - (v.centroid <=> o.embedding) END AS cosine_similarity
                  FROM obs o
                  JOIN vehicles v
                    ON v.plate_skeleton = o.plate_skeleton
                   AND o.plate_skeleton IS NOT NULL
                   AND v.last_seen_at >= o.first_seen_at - make_interval(secs => ?)
                   AND v.last_seen_at <= o.first_seen_at
            )
            SELECT * FROM via_edge
            UNION ALL
            SELECT * FROM fallback
            """;

    private final JdbcTemplate jdbcTemplate;
    private final IdentityProperties properties;

    public CandidateGateway(JdbcTemplate jdbcTemplate, IdentityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<CandidateVehicle> candidatesFor(long observationId) {
        // A vehicle can surface in both pools; the graph row wins because it carries the edge.
        Map<Long, CandidateVehicle> byVehicle = new LinkedHashMap<>();

        jdbcTemplate.query(SQL, rs -> {
            long vehicleId = rs.getLong("vehicle_id");
            long edgeId = rs.getLong("edge_id");
            Long edge = rs.wasNull() ? null : edgeId;
            int typical = rs.getInt("t_typical_s");
            Integer typicalSeconds = rs.wasNull() ? null : typical;
            double cosine = rs.getDouble("cosine_similarity");
            Double cosineSimilarity = rs.wasNull() ? null : cosine;

            CandidateVehicle candidate = new CandidateVehicle(
                    vehicleId,
                    rs.getString("canonical_plate"),
                    rs.getString("plate_skeleton"),
                    rs.getString("last_camera_id"),
                    rs.getDouble("elapsed_seconds"),
                    edge,
                    typicalSeconds,
                    cosineSimilarity);

            CandidateVehicle existing = byVehicle.get(vehicleId);
            if (existing == null || (existing.edgeId() == null && edge != null)) {
                byVehicle.put(vehicleId, candidate);
            }
        }, observationId, properties.getFallbackWindowSeconds());

        return new ArrayList<>(byVehicle.values());
    }

    /** The observation's own facts, for the resolver. */
    public ObservationFacts factsFor(long observationId) {
        List<ObservationFacts> rows = jdbcTemplate.query("""
                SELECT id, camera_id, first_seen_at, plate_canonical, plate_skeleton,
                       plate_char_confidences, (embedding IS NOT NULL) AS has_embedding
                  FROM observations WHERE id = ?
                """, (rs, rowNum) -> {
            java.sql.Array array = rs.getArray("plate_char_confidences");
            float[] confidences = null;
            if (array != null) {
                Object raw = array.getArray();
                if (raw instanceof Float[] boxed) {
                    confidences = new float[boxed.length];
                    for (int i = 0; i < boxed.length; i++) {
                        confidences[i] = boxed[i] == null ? Float.NaN : boxed[i];
                    }
                }
            }
            Timestamp firstSeen = rs.getTimestamp("first_seen_at");
            return new ObservationFacts(
                    rs.getLong("id"),
                    rs.getString("camera_id"),
                    firstSeen == null ? null : firstSeen.toInstant(),
                    rs.getString("plate_canonical"),
                    rs.getString("plate_skeleton"),
                    confidences,
                    rs.getBoolean("has_embedding"));
        }, observationId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
