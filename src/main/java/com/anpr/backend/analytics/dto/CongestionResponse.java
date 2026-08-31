package com.anpr.backend.analytics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record CongestionResponse(
        @JsonProperty("from") Instant from,
        @JsonProperty("to") Instant to,
        @JsonProperty("min_samples") int minSamples,
        /** Edges seen too few times to rank; reported rather than silently dropped. */
        @JsonProperty("excluded_low_sample_edges") int excludedLowSampleEdges,
        @JsonProperty("rows") List<Row> rows
) {
    public record Row(
            @JsonProperty("edge_id") long edgeId,
            @JsonProperty("from_camera_id") String fromCameraId,
            @JsonProperty("from_camera_name") String fromCameraName,
            @JsonProperty("to_camera_id") String toCameraId,
            @JsonProperty("to_camera_name") String toCameraName,
            @JsonProperty("median_duration_s") Integer medianDurationS,
            @JsonProperty("p85_duration_s") Integer p85DurationS,
            @JsonProperty("free_flow_s") Integer freeFlowS,
            @JsonProperty("congestion_ratio") Double congestionRatio,
            @JsonProperty("congestion_band") String congestionBand,
            @JsonProperty("sample_count") int sampleCount
    ) {}
}
