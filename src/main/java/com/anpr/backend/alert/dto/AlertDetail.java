package com.anpr.backend.alert.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Full alert context: the sighting that triggered it and both parties where relevant. */
public record AlertDetail(
        @JsonProperty("alert") AlertSummary alert,
        @JsonProperty("observation") Observation observation,
        @JsonProperty("vehicle") VehicleSummary vehicle,
        @JsonProperty("related_vehicle") VehicleSummary relatedVehicle
) {

    public record Observation(
            @JsonProperty("observation_id") long observationId,
            @JsonProperty("camera_id") String cameraId,
            @JsonProperty("first_seen_at") Instant firstSeenAt,
            @JsonProperty("dwell_ms") Integer dwellMs,
            @JsonProperty("plate_text") String plateText,
            @JsonProperty("plate_confidence") Float plateConfidence,
            @JsonProperty("vehicle_type") String vehicleType,
            @JsonProperty("vehicle_colour") String vehicleColour,
            @JsonProperty("snapshot_url") String snapshotUrl
    ) {}

    public record VehicleSummary(
            @JsonProperty("vehicle_id") long vehicleId,
            @JsonProperty("canonical_plate") String canonicalPlate,
            @JsonProperty("status") String status,
            @JsonProperty("sighting_count") int sightingCount,
            @JsonProperty("first_seen_at") Instant firstSeenAt,
            @JsonProperty("last_seen_at") Instant lastSeenAt,
            @JsonProperty("last_camera_id") String lastCameraId,
            @JsonProperty("vehicle_type") String vehicleType,
            @JsonProperty("vehicle_colour") String vehicleColour
    ) {}
}
