package com.anpr.backend.sim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param vehiclesPerMinute spawn rate
 * @param durationMinutes   0 means run until stopped
 * @param ocrErrorRate      null keeps the current rate
 * @param nullPlateRate     null keeps the current rate
 */
public record SimStartRequest(
        @JsonProperty("vehiclesPerMinute") Double vehiclesPerMinute,
        @JsonProperty("durationMinutes") Integer durationMinutes,
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
    public double vehiclesPerMinuteOrDefault() {
        return vehiclesPerMinute == null || vehiclesPerMinute <= 0 ? 6.0 : vehiclesPerMinute;
    }

    public int durationMinutesOrDefault() {
        return durationMinutes == null || durationMinutes < 0 ? 0 : durationMinutes;
    }
}
