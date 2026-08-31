package com.anpr.backend.identity;

import java.time.Instant;

/**
 * Everything the resolver needs to know about the observation being placed. Deliberately
 * free of JPA and JDBC types so the resolver can be exercised without a database.
 */
public record ObservationFacts(
        long observationId,
        String cameraId,
        Instant firstSeenAt,
        String plateCanonical,
        String plateSkeleton,
        float[] plateCharConfidences,
        boolean hasEmbedding
) {
    public boolean hasPlate() {
        return plateCanonical != null && !plateCanonical.isEmpty();
    }
}
