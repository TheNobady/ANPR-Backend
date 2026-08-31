package com.anpr.backend.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.List;

/** A vehicle and its ordered sightings. Plain JSON -- GeoJSON trajectories are Phase 4. */
public record VehicleDetailResponse(
        @JsonProperty("vehicle_id") long vehicleId,
        @JsonProperty("canonical_plate") String canonicalPlate,
        @JsonProperty("plate_skeleton") String plateSkeleton,
        @JsonProperty("status") String status,
        @JsonProperty("first_seen_at") Instant firstSeenAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("last_camera_id") String lastCameraId,
        @JsonProperty("sighting_count") int sightingCount,
        @JsonProperty("centroid_count") int centroidCount,
        @JsonProperty("sightings") List<Sighting> sightings
) {

    public record Sighting(
            @JsonProperty("sequence_number") int sequenceNumber,
            @JsonProperty("observation_id") long observationId,
            @JsonProperty("camera_id") String cameraId,
            @JsonProperty("camera_name") String cameraName,
            @JsonProperty("first_seen_at") Instant firstSeenAt,
            @JsonProperty("plate_text") String plateText,
            @JsonProperty("plate_canonical") String plateCanonical,
            @JsonProperty("plate_confidence") Float plateConfidence,
            @JsonProperty("link_confidence") Float linkConfidence,
            @JsonProperty("from_camera_id") String fromCameraId,
            @JsonRawValue @JsonProperty("score_breakdown") String scoreBreakdown
    ) {}
}
