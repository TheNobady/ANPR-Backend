package com.anpr.backend.observation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Read model for {@code GET /api/events/recent}. The 512-float embedding is
 * deliberately absent: it is large and of no use to a human debugging ingest.
 */
public record ObservationDto(
        @JsonProperty("id") Long id,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("camera_id") String cameraId,
        @JsonProperty("tracklet_id") String trackletId,
        @JsonProperty("first_seen_at") Instant firstSeenAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("dwell_ms") int dwellMs,
        @JsonProperty("plate_text") String plateText,
        @JsonProperty("plate_canonical") String plateCanonical,
        @JsonProperty("plate_confidence") Float plateConfidence,
        @JsonProperty("plate_char_confidences") List<Float> plateCharConfidences,
        @JsonProperty("plate_raw_reads") List<String> plateRawReads,
        @JsonProperty("vehicle_type") String vehicleType,
        @JsonProperty("vehicle_type_confidence") Float vehicleTypeConfidence,
        @JsonProperty("vehicle_colour") String vehicleColour,
        @JsonProperty("vehicle_colour_confidence") Float vehicleColourConfidence,
        @JsonProperty("bbox_last") List<Integer> bboxLast,
        @JsonProperty("detection_confidence") Float detectionConfidence,
        @JsonProperty("node_mode") String nodeMode,
        @JsonProperty("snapshot_url") String snapshotUrl,
        @JsonProperty("node_reported_lat") Double nodeReportedLat,
        @JsonProperty("node_reported_lon") Double nodeReportedLon,
        @JsonProperty("received_at") Instant receivedAt
) {

    public static ObservationDto from(Observation o) {
        return new ObservationDto(
                o.getId(),
                o.getEventId(),
                o.getCameraId(),
                o.getTrackletId(),
                o.getFirstSeenAt(),
                o.getLastSeenAt(),
                o.getDwellMs(),
                o.getPlateText(),
                o.getPlateCanonical(),
                o.getPlateConfidence(),
                boxFloats(o.getPlateCharConfidences()),
                o.getPlateRawReads() == null ? null : List.of(o.getPlateRawReads()),
                o.getVehicleType(),
                o.getVehicleTypeConfidence(),
                o.getVehicleColour(),
                o.getVehicleColourConfidence(),
                boxInts(o.getBboxLast()),
                o.getDetectionConfidence(),
                o.getNodeMode(),
                o.getSnapshotUrl(),
                o.getNodeReportedLat(),
                o.getNodeReportedLon(),
                o.getReceivedAt());
    }

    private static List<Float> boxFloats(float[] values) {
        if (values == null) {
            return null;
        }
        List<Float> out = new java.util.ArrayList<>(values.length);
        for (float v : values) {
            out.add(v);
        }
        return out;
    }

    private static List<Integer> boxInts(int[] values) {
        if (values == null) {
            return null;
        }
        List<Integer> out = new java.util.ArrayList<>(values.length);
        for (int v : values) {
            out.add(v);
        }
        return out;
    }
}
