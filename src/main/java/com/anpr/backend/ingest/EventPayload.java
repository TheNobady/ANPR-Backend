package com.anpr.backend.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Wire format produced by the perception nodes. The field names are owned by the
 * perception team -- they are mapped verbatim with {@link JsonProperty} and are
 * deliberately not renamed to Java conventions.
 */
public record EventPayload(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("camera_id") String cameraId,
        @JsonProperty("camera_location") CameraLocation cameraLocation,
        @JsonProperty("timestamp_first_seen") Instant timestampFirstSeen,
        @JsonProperty("timestamp_last_seen") Instant timestampLastSeen,
        @JsonProperty("tracklet_id") String trackletId,
        @JsonProperty("plate") Plate plate,
        @JsonProperty("vehicle") Vehicle vehicle,
        @JsonProperty("detection_confidence") Float detectionConfidence,
        @JsonProperty("node_mode") String nodeMode,
        @JsonProperty("snapshot_url") String snapshotUrl
) {

    public record CameraLocation(
            @JsonProperty("lat") Double lat,
            @JsonProperty("lon") Double lon,
            @JsonProperty("name") String name
    ) {
        public boolean hasCoordinates() {
            return lat != null && lon != null;
        }
    }

    /** May be absent entirely: a node still reports the event for its embedding. */
    public record Plate(
            @JsonProperty("text") String text,
            @JsonProperty("confidence") Float confidence,
            @JsonProperty("char_confidences") List<Float> charConfidences,
            @JsonProperty("raw_ocr_reads") List<String> rawOcrReads
    ) {}

    public record Vehicle(
            @JsonProperty("type") String type,
            @JsonProperty("type_confidence") Float typeConfidence,
            @JsonProperty("colour") String colour,
            @JsonProperty("colour_confidence") Float colourConfidence,
            @JsonProperty("embedding") List<Float> embedding,
            @JsonProperty("bbox_last") List<Integer> bboxLast
    ) {}
}
