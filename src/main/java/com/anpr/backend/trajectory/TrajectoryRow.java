package com.anpr.backend.trajectory;

import java.time.Instant;

/**
 * One row of the trajectory query: a sighting, plus the hop that arrived at it.
 *
 * <p>Pairing them in a single row is what keeps this to one query -- {@code edge_id} lives
 * on the trajectory event, which already means "the edge travelled to reach this sighting",
 * so the hop into sequence N and the sighting at sequence N are naturally the same row.
 */
record TrajectoryRow(
        int sequenceNumber,
        long observationId,
        String cameraId,
        String cameraName,
        String zone,
        Instant firstSeenAt,
        Integer dwellMs,
        String plateText,
        Float plateConfidence,
        String vehicleType,
        String vehicleColour,
        String snapshotUrl,
        String cameraGeoJson,
        // hop into this sighting; null on the first visible sighting
        Instant previousFirstSeenAt,
        String fromCameraId,
        Long edgeId,
        Float linkConfidence,
        String scoreBreakdown,
        Integer typicalSeconds,
        Double roadDistanceM,
        String[] intermediateCameras,
        String hopGeoJson,
        Double straightLineDistanceM
) {
    boolean hasHop() {
        return previousFirstSeenAt != null && hopGeoJson != null;
    }
}
