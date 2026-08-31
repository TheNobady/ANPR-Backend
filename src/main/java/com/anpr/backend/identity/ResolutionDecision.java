package com.anpr.backend.identity;

/**
 * What the resolver decided. It never writes -- persistence is somebody else's job, which
 * is what lets the decision logic be tested without a database.
 */
public sealed interface ResolutionDecision {

    /** Attach this observation to an existing vehicle. */
    record Link(long vehicleId, double total, double linkConfidence, ScoreBreakdown breakdown,
                String fromCameraId, Long edgeId) implements ResolutionDecision {
    }

    /**
     * Start a new vehicle.
     *
     * @param reason why -- no candidates, nothing over the threshold, or an ambiguous tie
     */
    record NewVehicle(String reason, Double bestRejectedTotal) implements ResolutionDecision {
    }
}
