package com.anpr.backend.observation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A single vehicle sighting at one camera.
 *
 * <p>Reads only. Inserts go through {@link ObservationWriter}, which speaks native SQL
 * so it can bind the pgvector column and use {@code ON CONFLICT} for idempotency.
 * The {@code embedding} column is deliberately unmapped: it is never returned by the
 * API and nothing in Phase 1 reads it back.
 */
@Entity
@Table(name = "observations")
@Getter
@Setter
public class Observation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "camera_id", nullable = false)
    private String cameraId;

    @Column(name = "tracklet_id", nullable = false)
    private String trackletId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "dwell_ms", nullable = false)
    private int dwellMs;

    @Column(name = "plate_text")
    private String plateText;

    @Column(name = "plate_confidence")
    private Float plateConfidence;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "plate_char_confidences")
    private float[] plateCharConfidences;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "plate_raw_reads")
    private String[] plateRawReads;

    @Column(name = "plate_canonical")
    private String plateCanonical;

    @Column(name = "vehicle_type")
    private String vehicleType;

    @Column(name = "vehicle_type_confidence")
    private Float vehicleTypeConfidence;

    @Column(name = "vehicle_colour")
    private String vehicleColour;

    @Column(name = "vehicle_colour_confidence")
    private Float vehicleColourConfidence;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "bbox_last")
    private int[] bboxLast;

    @Column(name = "detection_confidence")
    private Float detectionConfidence;

    @Column(name = "node_mode")
    private String nodeMode;

    @Column(name = "snapshot_url")
    private String snapshotUrl;

    @Column(name = "node_reported_lat")
    private Double nodeReportedLat;

    @Column(name = "node_reported_lon")
    private Double nodeReportedLon;

    @Column(name = "received_at", insertable = false, updatable = false)
    private Instant receivedAt;
}
