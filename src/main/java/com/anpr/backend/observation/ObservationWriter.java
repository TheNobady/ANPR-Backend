package com.anpr.backend.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * Native-SQL insert path for observations.
 *
 * <p>JPA is not used here for two reasons: the {@code embedding} column is a pgvector
 * type Hibernate has no mapping for, and idempotency is expressed as
 * {@code ON CONFLICT (camera_id, tracklet_id) DO NOTHING} so a duplicate tracklet costs
 * one statement rather than a select-then-insert.
 */
@Component
public class ObservationWriter {

    private static final String INSERT = """
            INSERT INTO observations (
                event_id, camera_id, tracklet_id,
                first_seen_at, last_seen_at, dwell_ms,
                plate_text, plate_confidence, plate_char_confidences, plate_raw_reads, plate_canonical, plate_skeleton,
                vehicle_type, vehicle_type_confidence, vehicle_colour, vehicle_colour_confidence,
                embedding, bbox_last, detection_confidence,
                node_mode, snapshot_url, node_reported_lat, node_reported_lon
            ) VALUES (
                ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                CAST(? AS vector), ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT (camera_id, tracklet_id) DO NOTHING
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public ObservationWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return the new observation's id, or empty when {@code (camera_id, tracklet_id)} was
     *         already present -- i.e. this delivery was a duplicate. The id is what the
     *         identity queue is handed.
     */
    @Transactional
    public java.util.Optional<Long> insert(ObservationInsert o) {
        List<Long> ids = jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT);
            int i = 1;
            ps.setString(i++, o.eventId());
            ps.setString(i++, o.cameraId());
            ps.setString(i++, o.trackletId());
            ps.setTimestamp(i++, Timestamp.from(o.firstSeenAt()));
            ps.setTimestamp(i++, Timestamp.from(o.lastSeenAt()));
            ps.setInt(i++, o.dwellMs());

            ps.setString(i++, o.plateText());
            setNullableFloat(ps, i++, o.plateConfidence());
            setFloatArray(ps, i++, o.plateCharConfidences());
            setTextArray(ps, i++, o.plateRawReads());
            ps.setString(i++, o.plateCanonical());
            ps.setString(i++, o.plateSkeleton());

            ps.setString(i++, o.vehicleType());
            setNullableFloat(ps, i++, o.vehicleTypeConfidence());
            ps.setString(i++, o.vehicleColour());
            setNullableFloat(ps, i++, o.vehicleColourConfidence());

            ps.setString(i++, o.embeddingLiteral());
            setIntArray(ps, i++, o.bboxLast());
            setNullableFloat(ps, i++, o.detectionConfidence());

            ps.setString(i++, o.nodeMode());
            ps.setString(i++, o.snapshotUrl());
            setNullableDouble(ps, i++, o.nodeReportedLat());
            setNullableDouble(ps, i, o.nodeReportedLon());
            return ps;
        }, (rs, rowNum) -> rs.getLong("id"));
        return ids.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ids.get(0));
    }

    private static void setNullableFloat(PreparedStatement ps, int index, Float value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setFloat(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setFloatArray(PreparedStatement ps, int index, float[] values) throws SQLException {
        if (values == null) {
            ps.setNull(index, Types.ARRAY);
            return;
        }
        Float[] boxed = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        Array array = ps.getConnection().createArrayOf("real", boxed);
        ps.setArray(index, array);
    }

    private static void setIntArray(PreparedStatement ps, int index, int[] values) throws SQLException {
        if (values == null) {
            ps.setNull(index, Types.ARRAY);
            return;
        }
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        Array array = ps.getConnection().createArrayOf("integer", boxed);
        ps.setArray(index, array);
    }

    private static void setTextArray(PreparedStatement ps, int index, String[] values) throws SQLException {
        if (values == null) {
            ps.setNull(index, Types.ARRAY);
            return;
        }
        Array array = ps.getConnection().createArrayOf("text", values);
        ps.setArray(index, array);
    }
}
