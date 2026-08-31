package com.anpr.backend.identity;

import com.anpr.backend.identity.dto.VehicleDetailResponse;
import com.anpr.backend.identity.dto.VehicleSearchResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VehicleQueryService {

    private final JdbcTemplate jdbcTemplate;

    public VehicleQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public VehicleDetailResponse findById(long vehicleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM vehicles WHERE id = ?", vehicleId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> v = rows.get(0);

        List<VehicleDetailResponse.Sighting> sightings = jdbcTemplate.query("""
                SELECT te.sequence_number, te.observation_id, te.link_confidence,
                       te.from_camera_id, te.score_breakdown::text AS score_breakdown,
                       o.camera_id, o.first_seen_at, o.plate_text, o.plate_canonical,
                       o.plate_confidence, c.name AS camera_name
                  FROM trajectory_events te
                  JOIN observations o ON o.id = te.observation_id
                  LEFT JOIN cameras c ON c.camera_id = o.camera_id
                 WHERE te.vehicle_id = ?
                 ORDER BY te.sequence_number
                """, (rs, n) -> new VehicleDetailResponse.Sighting(
                        rs.getInt("sequence_number"),
                        rs.getLong("observation_id"),
                        rs.getString("camera_id"),
                        rs.getString("camera_name"),
                        instant(rs.getTimestamp("first_seen_at")),
                        rs.getString("plate_text"),
                        rs.getString("plate_canonical"),
                        nullableFloat(rs.getFloat("plate_confidence"), rs.wasNull()),
                        nullableFloat(rs.getFloat("link_confidence"), rs.wasNull()),
                        rs.getString("from_camera_id"),
                        rs.getString("score_breakdown") == null ? "null" : rs.getString("score_breakdown")),
                vehicleId);

        return new VehicleDetailResponse(
                ((Number) v.get("id")).longValue(),
                (String) v.get("canonical_plate"),
                (String) v.get("plate_skeleton"),
                (String) v.get("status"),
                instant((Timestamp) v.get("first_seen_at")),
                instant((Timestamp) v.get("last_seen_at")),
                (String) v.get("last_camera_id"),
                ((Number) v.getOrDefault("sighting_count", 0)).intValue(),
                ((Number) v.getOrDefault("centroid_count", 0)).intValue(),
                sightings);
    }

    /**
     * One projection, three predicates. Everything a results panel shows is selected here,
     * including the journey's first camera via a lateral join, so the frontend never needs a
     * follow-up call per row.
     *
     * <p>{@code %s} placeholders are the tier's predicate and ordering -- never user input,
     * which is always bound.
     */
    private static final String SEARCH_SQL = """
            SELECT v.id, v.canonical_plate, v.status, v.sighting_count,
                   v.first_seen_at, v.last_seen_at, v.last_camera_id,
                   fc.camera_id AS first_camera_id,
                   similarity(COALESCE(v.canonical_plate, ''), ?) AS match_score
              FROM vehicles v
              LEFT JOIN LATERAL (
                    SELECT o.camera_id
                      FROM trajectory_events te
                      JOIN observations o ON o.id = te.observation_id
                     WHERE te.vehicle_id = v.id
                     ORDER BY te.sequence_number
                     LIMIT 1
              ) fc ON true
             WHERE %s
               AND (?::timestamptz IS NULL OR v.last_seen_at  >= ?::timestamptz)
               AND (?::timestamptz IS NULL OR v.first_seen_at <= ?::timestamptz)
             ORDER BY %s
             LIMIT ?
            """;

    /**
     * Three tiers, most certain first: exact canonical, then skeleton (the same plate read
     * through a confusable glyph), then trigram similarity for a genuinely garbled query.
     * A vehicle is reported once, under the strongest tier that found it, and results stay
     * in tier order so a caller can trust the ranking without re-sorting.
     *
     * <p>{@code from}/{@code to} clip on overlap: a vehicle matches if it was active at any
     * point in the window.
     */
    public List<VehicleSearchResult> search(String plateQuery, Instant from, Instant to, int limit) {
        String canonical = plateQuery == null ? "" : plateQuery.toUpperCase()
                .replace(" ", "").replace("-", "");
        if (canonical.isEmpty()) {
            return List.of();
        }
        String skeleton = PlateSkeleton.of(canonical);

        Map<Long, VehicleSearchResult> seen = new LinkedHashMap<>();
        collect(seen, limit, "exact",
                SEARCH_SQL.formatted("v.canonical_plate = ?", "v.last_seen_at DESC"),
                canonical, canonical, from, to);

        if (seen.size() < limit) {
            collect(seen, limit, "skeleton",
                    SEARCH_SQL.formatted("v.plate_skeleton = ?", "v.last_seen_at DESC"),
                    canonical, skeleton, from, to);
        }
        if (seen.size() < limit) {
            collect(seen, limit, "fuzzy",
                    SEARCH_SQL.formatted(
                            // single %: this is an argument to formatted(), not part of
                            // the format string, so it is not unescaped again
                            "v.canonical_plate IS NOT NULL AND v.canonical_plate % ?",
                            "match_score DESC, v.last_seen_at DESC"),
                    canonical, canonical, from, to);
        }
        return new ArrayList<>(seen.values());
    }

    private void collect(Map<Long, VehicleSearchResult> into, int limit, String matchType,
                         String sql, String similarityArg, String predicateArg,
                         Instant from, Instant to) {
        Timestamp fromTs = from == null ? null : Timestamp.from(from);
        Timestamp toTs = to == null ? null : Timestamp.from(to);
        jdbcTemplate.query(sql, rs -> {
            if (into.size() >= limit || into.containsKey(rs.getLong("id"))) {
                return;
            }
            long id = rs.getLong("id");
            double scoreValue = rs.getDouble("match_score");
            // wasNull() reports on the most recent getter, so read it here rather than
            // further down the argument list.
            Double matchScore = rs.wasNull() ? null : scoreValue;
            into.put(id, new VehicleSearchResult(
                    id,
                    rs.getString("canonical_plate"),
                    matchType,
                    "exact".equals(matchType) ? 1.0 : matchScore,
                    rs.getInt("sighting_count"),
                    instant(rs.getTimestamp("first_seen_at")),
                    instant(rs.getTimestamp("last_seen_at")),
                    rs.getString("first_camera_id"),
                    rs.getString("last_camera_id"),
                    rs.getString("status")));
        }, similarityArg, predicateArg, fromTs, fromTs, toTs, toTs, limit);
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Float nullableFloat(float value, boolean wasNull) {
        return wasNull ? null : value;
    }
}
