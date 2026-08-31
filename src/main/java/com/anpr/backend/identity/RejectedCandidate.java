package com.anpr.backend.identity;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A candidate the resolver considered and did not pick, with enough of the reasoning to act
 * on. Only candidates whose plate matched exactly or by skeleton are reported: those are the
 * ones whose rejection means something.
 *
 * @param plateRule         which plate rule matched -- {@code exact} or {@code skeleton}
 * @param cosineSimilarity  raw appearance similarity, or null if either side had no embedding
 */
public record RejectedCandidate(
        @JsonProperty("vehicle_id") long vehicleId,
        @JsonProperty("canonical_plate") String canonicalPlate,
        @JsonProperty("last_camera_id") String lastCameraId,
        @JsonProperty("rejection_reason") RejectionReason rejectionReason,
        @JsonProperty("plate_rule") String plateRule,
        @JsonProperty("total_score") double totalScore,
        @JsonProperty("appearance_score") double appearanceScore,
        @JsonProperty("cosine_similarity") Double cosineSimilarity,
        @JsonProperty("elapsed_seconds") double elapsedSeconds,
        @JsonProperty("via_edge") boolean viaEdge
) {}
