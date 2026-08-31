package com.anpr.backend.identity;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Why the resolver scored a candidate the way it did. Stored on the trajectory event. */
public record ScoreBreakdown(
        @JsonProperty("plate") double plate,
        @JsonProperty("travel_time") double travelTime,
        @JsonProperty("appearance") double appearance,
        @JsonProperty("total") double total,
        @JsonProperty("plate_rule") String plateRule,
        @JsonProperty("plate_distance") Double plateDistance,
        @JsonProperty("cosine_similarity") Double cosineSimilarity,
        @JsonProperty("elapsed_seconds") Double elapsedSeconds,
        @JsonProperty("typical_seconds") Integer typicalSeconds,
        @JsonProperty("runner_up_total") Double runnerUpTotal
) {
    public ScoreBreakdown withRunnerUp(Double runnerUp) {
        return new ScoreBreakdown(plate, travelTime, appearance, total, plateRule, plateDistance,
                cosineSimilarity, elapsedSeconds, typicalSeconds, runnerUp);
    }
}
