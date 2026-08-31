package com.anpr.backend.trajectory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The whole trajectory in one query.
 *
 * <p>Joins trajectory_events, observations, cameras (twice -- the sighting's camera and the
 * hop's origin) and camera_edges, and renders every geometry with {@code ST_AsGeoJSON} so
 * nothing has to be deserialised into Java and serialised again. A query per hop would turn
 * a five-sighting trajectory into nine round trips.
 */
@Repository
public class TrajectoryRepository {

    private static final String SQL = """
            SELECT te.sequence_number,
                   te.observation_id,
                   te.from_camera_id,
                   te.edge_id,
                   te.link_confidence,
                   te.score_breakdown::text                      AS score_breakdown,
                   o.camera_id,
                   o.first_seen_at,
                   o.dwell_ms,
                   o.plate_text,
                   o.plate_confidence,
                   o.vehicle_type,
                   o.vehicle_colour,
                   o.snapshot_url,
                   c.name                                        AS camera_name,
                   c.zone,
                   ST_AsGeoJSON(c.geom)                          AS camera_geojson,
                   -- the previous *visible* sighting, so a clipped range does not invent a hop
                   lag(o.first_seen_at) OVER w                   AS previous_first_seen_at,
                   e.t_typical_s,
                   e.road_distance_m,
                   e.intermediate_cameras,
                   CASE
                       WHEN e.id IS NOT NULL THEN ST_AsGeoJSON(e.path_geom)
                       WHEN fc.geom IS NOT NULL AND lag(o.first_seen_at) OVER w IS NOT NULL
                            THEN ST_AsGeoJSON(ST_MakeLine(fc.geom, c.geom))
                   END                                           AS hop_geojson,
                   CASE
                       WHEN e.id IS NULL AND fc.geom IS NOT NULL
                            THEN ST_Distance(fc.geom::geography, c.geom::geography)
                   END                                           AS straight_line_distance_m
              FROM trajectory_events te
              JOIN observations o  ON o.id = te.observation_id
              LEFT JOIN cameras c  ON c.camera_id = o.camera_id
              LEFT JOIN cameras fc ON fc.camera_id = te.from_camera_id
              LEFT JOIN camera_edges e ON e.id = te.edge_id
             WHERE te.vehicle_id = ?
               AND (?::timestamptz IS NULL OR o.first_seen_at >= ?::timestamptz)
               AND (?::timestamptz IS NULL OR o.first_seen_at <= ?::timestamptz)
            WINDOW w AS (ORDER BY te.sequence_number)
             ORDER BY te.sequence_number
            """;

    private final JdbcTemplate jdbcTemplate;

    public TrajectoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<TrajectoryRow> findByVehicle(long vehicleId, Instant from, Instant to) {
        Timestamp fromTs = from == null ? null : Timestamp.from(from);
        Timestamp toTs = to == null ? null : Timestamp.from(to);
        return jdbcTemplate.query(SQL, (rs, rowNum) -> {
            java.sql.Array intermediates = rs.getArray("intermediate_cameras");
            String[] skipped = intermediates == null ? new String[0] : (String[]) intermediates.getArray();

            long edgeIdValue = rs.getLong("edge_id");
            Long edgeId = rs.wasNull() ? null : edgeIdValue;
            float linkConfidenceValue = rs.getFloat("link_confidence");
            Float linkConfidence = rs.wasNull() ? null : linkConfidenceValue;
            int typicalValue = rs.getInt("t_typical_s");
            Integer typical = rs.wasNull() ? null : typicalValue;
            double roadDistanceValue = rs.getDouble("road_distance_m");
            Double roadDistance = rs.wasNull() ? null : roadDistanceValue;
            int dwellValue = rs.getInt("dwell_ms");
            Integer dwell = rs.wasNull() ? null : dwellValue;
            float plateConfidenceValue = rs.getFloat("plate_confidence");
            Float plateConfidence = rs.wasNull() ? null : plateConfidenceValue;
            double straightValue = rs.getDouble("straight_line_distance_m");
            Double straight = rs.wasNull() ? null : straightValue;

            Timestamp previous = rs.getTimestamp("previous_first_seen_at");
            Timestamp seen = rs.getTimestamp("first_seen_at");

            return new TrajectoryRow(
                    rs.getInt("sequence_number"),
                    rs.getLong("observation_id"),
                    rs.getString("camera_id"),
                    rs.getString("camera_name"),
                    rs.getString("zone"),
                    seen == null ? null : seen.toInstant(),
                    dwell,
                    rs.getString("plate_text"),
                    plateConfidence,
                    rs.getString("vehicle_type"),
                    rs.getString("vehicle_colour"),
                    rs.getString("snapshot_url"),
                    rs.getString("camera_geojson"),
                    previous == null ? null : previous.toInstant(),
                    rs.getString("from_camera_id"),
                    edgeId,
                    linkConfidence,
                    rs.getString("score_breakdown"),
                    typical,
                    roadDistance,
                    skipped,
                    rs.getString("hop_geojson"),
                    straight);
        }, vehicleId, fromTs, fromTs, toTs, toTs);
    }
}
