package com.anpr.backend.alert;

import com.anpr.backend.alert.dto.AlertDetail;
import com.anpr.backend.alert.dto.AlertStats;
import com.anpr.backend.alert.dto.AlertSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertQueryService {

    /**
     * One projection for both the feed and the detail view: an alert row is useless without
     * the camera it fired at and the plate it concerns, so they are joined in rather than
     * fetched per row.
     */
    private static final String SELECT = """
            SELECT a.id, a.alert_type, a.severity, a.vehicle_id, a.related_vehicle_id,
                   a.observation_id, a.camera_id, a.occurred_at, a.created_at,
                   a.repeat_count, a.message, a.metadata::text AS metadata,
                   c.name AS camera_name, c.zone, c.lat, c.lon,
                   COALESCE(v.canonical_plate, o.plate_canonical) AS plate
              FROM alerts a
              LEFT JOIN cameras c      ON c.camera_id = a.camera_id
              LEFT JOIN vehicles v     ON v.id = a.vehicle_id
              LEFT JOIN observations o ON o.id = a.observation_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public AlertQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertSummary> find(String type, String severity, Instant from, Instant to,
                                   Long vehicleId, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" AND a.alert_type = ?");
            args.add(type);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND a.severity = ?");
            args.add(severity);
        }
        if (from != null) {
            sql.append(" AND a.occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND a.occurred_at <= ?");
            args.add(Timestamp.from(to));
        }
        if (vehicleId != null) {
            sql.append(" AND (a.vehicle_id = ? OR a.related_vehicle_id = ?)");
            args.add(vehicleId);
            args.add(vehicleId);
        }
        sql.append(" ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), summaryMapper(), args.toArray());
    }

    /** @return null when there is no such alert, so the controller can 404 */
    public AlertDetail detail(long alertId) {
        List<AlertSummary> rows = jdbcTemplate.query(
                SELECT + " WHERE a.id = ?", summaryMapper(), alertId);
        if (rows.isEmpty()) {
            return null;
        }
        AlertSummary alert = rows.get(0);
        return new AlertDetail(
                alert,
                alert.observationId() == null ? null : observation(alert.observationId()),
                alert.vehicleId() == null ? null : vehicle(alert.vehicleId()),
                // Both parties, so a clone alert reads as a comparison rather than a claim.
                alert.relatedVehicleId() == null ? null : vehicle(alert.relatedVehicleId()));
    }

    public AlertStats stats(Instant from, Instant to) {
        String where = " WHERE (?::timestamptz IS NULL OR occurred_at >= ?::timestamptz)"
                + " AND (?::timestamptz IS NULL OR occurred_at <= ?::timestamptz)";
        Timestamp fromTs = from == null ? null : Timestamp.from(from);
        Timestamp toTs = to == null ? null : Timestamp.from(to);
        Object[] args = {fromTs, fromTs, toTs, toTs};

        Map<String, Long> byType = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT alert_type, count(*) AS n FROM alerts" + where
                        + " GROUP BY alert_type ORDER BY n DESC", args)
                .forEach(r -> byType.put((String) r.get("alert_type"), ((Number) r.get("n")).longValue()));

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT severity, count(*) AS n FROM alerts" + where
                        + " GROUP BY severity", args)
                .forEach(r -> bySeverity.put((String) r.get("severity"), ((Number) r.get("n")).longValue()));

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM alerts" + where, Long.class, args);
        return new AlertStats(total == null ? 0 : total, byType, bySeverity, from, to);
    }

    private RowMapper<AlertSummary> summaryMapper() {
        return (rs, n) -> {
            long vehicleIdValue = rs.getLong("vehicle_id");
            Long vehicleId = rs.wasNull() ? null : vehicleIdValue;
            long relatedValue = rs.getLong("related_vehicle_id");
            Long related = rs.wasNull() ? null : relatedValue;
            long observationValue = rs.getLong("observation_id");
            Long observationId = rs.wasNull() ? null : observationValue;
            double latValue = rs.getDouble("lat");
            Double lat = rs.wasNull() ? null : latValue;
            double lonValue = rs.getDouble("lon");
            Double lon = rs.wasNull() ? null : lonValue;
            String metadata = rs.getString("metadata");
            return new AlertSummary(
                    rs.getLong("id"), rs.getString("alert_type"), rs.getString("severity"),
                    vehicleId, related, rs.getString("plate"), observationId,
                    rs.getString("camera_id"), rs.getString("camera_name"), rs.getString("zone"),
                    lat, lon,
                    instant(rs.getTimestamp("occurred_at")), instant(rs.getTimestamp("created_at")),
                    rs.getInt("repeat_count"), rs.getString("message"),
                    metadata == null ? "null" : metadata);
        };
    }

    private AlertDetail.Observation observation(long observationId) {
        List<AlertDetail.Observation> rows = jdbcTemplate.query("""
                SELECT id, camera_id, first_seen_at, dwell_ms, plate_text, plate_confidence,
                       vehicle_type, vehicle_colour, snapshot_url
                  FROM observations WHERE id = ?
                """, (rs, n) -> {
            float confidence = rs.getFloat("plate_confidence");
            Float plateConfidence = rs.wasNull() ? null : confidence;
            int dwell = rs.getInt("dwell_ms");
            Integer dwellMs = rs.wasNull() ? null : dwell;
            return new AlertDetail.Observation(rs.getLong("id"), rs.getString("camera_id"),
                    instant(rs.getTimestamp("first_seen_at")), dwellMs, rs.getString("plate_text"),
                    plateConfidence, rs.getString("vehicle_type"), rs.getString("vehicle_colour"),
                    rs.getString("snapshot_url"));
        }, observationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AlertDetail.VehicleSummary vehicle(long vehicleId) {
        List<AlertDetail.VehicleSummary> rows = jdbcTemplate.query("""
                SELECT v.id, v.canonical_plate, v.status, v.sighting_count,
                       v.first_seen_at, v.last_seen_at, v.last_camera_id,
                       lo.vehicle_type, lo.vehicle_colour
                  FROM vehicles v
                  LEFT JOIN LATERAL (
                        SELECT o.vehicle_type, o.vehicle_colour
                          FROM trajectory_events te JOIN observations o ON o.id = te.observation_id
                         WHERE te.vehicle_id = v.id
                         ORDER BY te.sequence_number DESC LIMIT 1
                  ) lo ON true
                 WHERE v.id = ?
                """, (rs, n) -> new AlertDetail.VehicleSummary(
                        rs.getLong("id"), rs.getString("canonical_plate"), rs.getString("status"),
                        rs.getInt("sighting_count"), instant(rs.getTimestamp("first_seen_at")),
                        instant(rs.getTimestamp("last_seen_at")), rs.getString("last_camera_id"),
                        rs.getString("vehicle_type"), rs.getString("vehicle_colour")),
                vehicleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
