package com.anpr.backend.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Every rate ships with the counts it was computed from, so a figure derived from six
 * samples is visibly not the same kind of number as one derived from six thousand.
 */
public record EvaluationResponse(
        @JsonProperty("observations_with_ground_truth") long observationsWithGroundTruth,
        @JsonProperty("true_vehicles") long trueVehicles,
        @JsonProperty("resolved_vehicles") long resolvedVehicles,
        @JsonProperty("link_precision") Rate linkPrecision,
        @JsonProperty("link_recall") Rate linkRecall,
        @JsonProperty("fragmentation") Fragmentation fragmentation,
        @JsonProperty("id_purity") Rate idPurity,
        @JsonProperty("by_plate_present") Split byPlatePresent,
        @JsonProperty("by_plate_null") Split byPlateNull
) {

    /** @param rate numerator/denominator, or null when the denominator is zero */
    public record Rate(
            @JsonProperty("rate") Double rate,
            @JsonProperty("numerator") long numerator,
            @JsonProperty("denominator") long denominator
    ) {
        public static Rate of(long numerator, long denominator) {
            return new Rate(denominator == 0 ? null : (double) numerator / denominator,
                    numerator, denominator);
        }
    }

    public record Fragmentation(
            @JsonProperty("mean_resolved_ids_per_true_vehicle") Double mean,
            @JsonProperty("true_vehicles") long trueVehicles,
            @JsonProperty("distinct_resolved_ids") long distinctResolvedIds
    ) {}

    /** Precision and recall restricted to links whose arriving observation had / lacked a plate. */
    public record Split(
            @JsonProperty("link_precision") Rate linkPrecision,
            @JsonProperty("link_recall") Rate linkRecall
    ) {}
}
