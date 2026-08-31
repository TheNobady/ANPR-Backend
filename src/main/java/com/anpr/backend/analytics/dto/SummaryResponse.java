package com.anpr.backend.analytics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/** Headline numbers for a dashboard strip. */
public record SummaryResponse(
        @JsonProperty("from") Instant from,
        @JsonProperty("to") Instant to,
        @JsonProperty("total_observations") long totalObservations,
        @JsonProperty("unique_vehicles") long uniqueVehicles,
        @JsonProperty("active_cameras_reporting") long activeCamerasReporting,
        @JsonProperty("alerts_by_severity") Map<String, Long> alertsBySeverity,
        @JsonProperty("busiest_camera") BusiestCamera busiestCamera,
        @JsonProperty("worst_congested_edge") WorstEdge worstCongestedEdge,
        @JsonProperty("mean_congestion_ratio") Double meanCongestionRatio
) {
    public record BusiestCamera(
            @JsonProperty("camera_id") String cameraId,
            @JsonProperty("camera_name") String cameraName,
            @JsonProperty("observation_count") long observationCount
    ) {}

    public record WorstEdge(
            @JsonProperty("edge_id") long edgeId,
            @JsonProperty("from_camera_id") String fromCameraId,
            @JsonProperty("to_camera_id") String toCameraId,
            @JsonProperty("congestion_ratio") Double congestionRatio,
            @JsonProperty("congestion_band") String congestionBand,
            @JsonProperty("sample_count") int sampleCount
    ) {}
}
