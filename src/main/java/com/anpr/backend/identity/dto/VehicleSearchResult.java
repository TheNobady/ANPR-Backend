package com.anpr.backend.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One search hit, carrying everything a results panel shows so the frontend never has to
 * make a follow-up call per row.
 *
 * @param matchType  {@code exact}, {@code skeleton} or {@code fuzzy} -- which tier found it,
 *                   so a caller can tell a certainty from a guess
 * @param matchScore trigram similarity to the query, 1.0 for an exact match
 */
public record VehicleSearchResult(
        @JsonProperty("vehicle_id") long vehicleId,
        @JsonProperty("canonical_plate") String canonicalPlate,
        @JsonProperty("match_type") String matchType,
        @JsonProperty("match_score") Double matchScore,
        @JsonProperty("sighting_count") int sightingCount,
        @JsonProperty("first_seen_at") Instant firstSeenAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("first_camera_id") String firstCameraId,
        @JsonProperty("last_camera_id") String lastCameraId,
        @JsonProperty("status") String status
) {}
