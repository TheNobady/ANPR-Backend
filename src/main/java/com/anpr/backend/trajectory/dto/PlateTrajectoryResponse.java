package com.anpr.backend.trajectory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Plate-keyed trajectory lookup.
 *
 * <p>"No vehicle matched that plate" is a legitimate search result, not an error, so this
 * comes back 200 with a null trajectory rather than 404.
 */
public record PlateTrajectoryResponse(
        @JsonProperty("query") String query,
        @JsonProperty("matched_plate") String matchedPlate,
        @JsonProperty("match_type") String matchType,
        @JsonProperty("trajectory") TrajectoryResponse trajectory,
        @JsonProperty("alternate_matches") List<AlternateMatch> alternateMatches
) {

    public record AlternateMatch(
            @JsonProperty("vehicle_id") long vehicleId,
            @JsonProperty("canonical_plate") String canonicalPlate,
            @JsonProperty("match_type") String matchType,
            @JsonProperty("sighting_count") int sightingCount,
            @JsonProperty("last_seen_at") Instant lastSeenAt
    ) {}
}
