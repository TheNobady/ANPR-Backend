package com.anpr.backend.analytics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Traffic weight per edge, as GeoJSON.
 *
 * <p>{@code normalized} and {@code max_weight} are carried so the frontend can colour the
 * first feature without scanning the whole collection for a maximum, and can draw a legend.
 * {@code sample_count} lets it grey out edges too sparse to trust rather than colouring them
 * as confidently as everything else.
 */
public record HeatmapResponse(
        @JsonProperty("type") String type,
        @JsonProperty("as_of") Instant asOf,
        @JsonProperty("from") Instant from,
        @JsonProperty("to") Instant to,
        @JsonProperty("bucket_minutes") int bucketMinutes,
        @JsonProperty("max_weight") long maxWeight,
        @JsonProperty("edge_count") int edgeCount,
        @JsonProperty("features") List<Feature> features
) {
    public record Feature(
            @JsonProperty("type") String type,
            @JsonRawValue @JsonProperty("geometry") String geometry,
            @JsonProperty("properties") Map<String, Object> properties
    ) {}
}
