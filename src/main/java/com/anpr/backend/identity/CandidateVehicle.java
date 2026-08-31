package com.anpr.backend.identity;

/**
 * A vehicle the gating stage thinks could be this observation, with everything the
 * scorer needs already computed in SQL.
 *
 * @param elapsedSeconds    time since the vehicle was last seen
 * @param edgeId            the edge it would have travelled, or null for a fallback-pool candidate
 * @param typicalSeconds    that edge's expected travel time, or null
 * @param cosineSimilarity  similarity to the vehicle's centroid, or null if either side lacks one
 */
public record CandidateVehicle(
        long vehicleId,
        String canonicalPlate,
        String plateSkeleton,
        String lastCameraId,
        double elapsedSeconds,
        Long edgeId,
        Integer typicalSeconds,
        Double cosineSimilarity
) {
    public boolean viaEdge() {
        return edgeId != null && typicalSeconds != null;
    }
}
