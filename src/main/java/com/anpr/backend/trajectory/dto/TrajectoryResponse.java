package com.anpr.backend.trajectory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A vehicle's trajectory: summary numbers plus the GeoJSON the map draws.
 *
 * <p>Nothing here is a presentation value. There are no colours, widths or style hints --
 * the server reports what is true (confidence, whether geometry is real road or a straight
 * line, whether a detour is suspected) and the client decides how that looks.
 */
public record TrajectoryResponse(
        @JsonProperty("vehicle_id") long vehicleId,
        @JsonProperty("canonical_plate") String canonicalPlate,
        @JsonProperty("status") String status,
        @JsonProperty("sighting_count") int sightingCount,
        @JsonProperty("first_seen_at") Instant firstSeenAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("min_link_confidence") Double minLinkConfidence,
        @JsonProperty("mean_link_confidence") Double meanLinkConfidence,
        @JsonProperty("total_distance_m") Double totalDistanceM,
        @JsonProperty("total_duration_s") Long totalDurationS,
        @JsonProperty("geojson") TrajectoryGeoJson geojson
) {}
