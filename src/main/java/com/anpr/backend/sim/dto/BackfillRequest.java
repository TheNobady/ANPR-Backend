package com.anpr.backend.sim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param hours           how much history to generate, ending now
 * @param vehiclesPerHour how many journeys start in each of those hours
 */
public record BackfillRequest(
        @JsonProperty("hours") Double hours,
        @JsonProperty("vehiclesPerHour") Integer vehiclesPerHour,
        @JsonProperty("ocrErrorRate") Double ocrErrorRate,
        @JsonProperty("nullPlateRate") Double nullPlateRate,
        /** Per-component embedding noise. Null keeps the current value. */
        @JsonProperty("embeddingNoiseSigma") Double embeddingNoiseSigma,
        /**
         * Slows traffic down. 1.0 is free flow; 2.5 makes the worst corridors run at two and
         * a half times their free-flow time. The slowdown is spread unevenly across edges by
         * a stable hash, so the congestion analytics gets a range of bands rather than one
         * uniform shift -- which is what a congestion map is supposed to show.
         */
        @JsonProperty("congestionFactor") Double congestionFactor
) {
    public double hoursOrDefault() {
        return hours == null || hours <= 0 ? 2.0 : hours;
    }

    public int vehiclesPerHourOrDefault() {
        return vehiclesPerHour == null || vehiclesPerHour <= 0 ? 300 : vehiclesPerHour;
    }
}
