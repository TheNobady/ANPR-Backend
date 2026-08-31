package com.anpr.backend.sim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The perception contract, written from the client side.
 *
 * <p>This deliberately duplicates the shape of the server's ingest DTO rather than
 * importing it. The simulator is a client of {@code POST /api/events} exactly as a
 * camera node is; if the two definitions ever drift, the simulator starts collecting
 * rejects and we find out immediately -- which is the whole point of it speaking real
 * HTTP instead of calling the service in-process.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SimEventPayload(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("camera_id") String cameraId,
        @JsonProperty("camera_location") CameraLocation cameraLocation,
        @JsonProperty("timestamp_first_seen") String timestampFirstSeen,
        @JsonProperty("timestamp_last_seen") String timestampLastSeen,
        @JsonProperty("tracklet_id") String trackletId,
        @JsonProperty("plate") Plate plate,
        @JsonProperty("vehicle") Vehicle vehicle,
        @JsonProperty("detection_confidence") double detectionConfidence,
        @JsonProperty("node_mode") String nodeMode,
        @JsonProperty("snapshot_url") String snapshotUrl
) {

    public record CameraLocation(
            @JsonProperty("lat") Double lat,
            @JsonProperty("lon") Double lon,
            @JsonProperty("name") String name
    ) {}

    public record Plate(
            @JsonProperty("text") String text,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("char_confidences") List<Double> charConfidences,
            @JsonProperty("raw_ocr_reads") List<String> rawOcrReads
    ) {}

    public record Vehicle(
            @JsonProperty("type") String type,
            @JsonProperty("type_confidence") double typeConfidence,
            @JsonProperty("colour") String colour,
            @JsonProperty("colour_confidence") double colourConfidence,
            @JsonProperty("embedding") List<Float> embedding,
            @JsonProperty("bbox_last") List<Integer> bboxLast
    ) {}
}
