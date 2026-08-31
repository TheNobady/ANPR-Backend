package com.anpr.backend.analytics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record DensityResponse(
        @JsonProperty("from") Instant from,
        @JsonProperty("to") Instant to,
        @JsonProperty("bucket_minutes") int bucketMinutes,
        @JsonProperty("series") List<Series> series
) {
    /** @param cameraId null on the synthetic city-wide series */
    public record Series(
            @JsonProperty("camera_id") String cameraId,
            @JsonProperty("camera_name") String cameraName,
            @JsonProperty("zone") String zone,
            @JsonProperty("points") List<Point> points
    ) {}

    public record Point(
            @JsonProperty("bucket_start") Instant bucketStart,
            @JsonProperty("observation_count") long observationCount,
            @JsonProperty("unique_vehicle_count") long uniqueVehicleCount,
            @JsonProperty("mean_dwell_ms") Integer meanDwellMs
    ) {}
}
