package com.anpr.backend.alert.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;

/** A feed row. Carries everything needed to render it without a follow-up call. */
public record AlertSummary(
        @JsonProperty("alert_id") long alertId,
        @JsonProperty("alert_type") String alertType,
        @JsonProperty("severity") String severity,
        @JsonProperty("vehicle_id") Long vehicleId,
        @JsonProperty("related_vehicle_id") Long relatedVehicleId,
        @JsonProperty("plate") String plate,
        @JsonProperty("observation_id") Long observationId,
        @JsonProperty("camera_id") String cameraId,
        @JsonProperty("camera_name") String cameraName,
        @JsonProperty("zone") String zone,
        @JsonProperty("lat") Double lat,
        @JsonProperty("lon") Double lon,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("repeat_count") int repeatCount,
        @JsonProperty("message") String message,
        @JsonRawValue @JsonProperty("metadata") String metadata
) {}
