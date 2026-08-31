package com.anpr.backend.analytics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Origin-destination flows between zones.
 *
 * <p>The {@code coverage} block is not optional. This matrix is built from vehicles observed
 * at two or more cameras, which systematically under-counts short trips and vehicles the
 * resolver never linked. Reporting that denominator is a credibility gain; a reader who finds
 * an unstated bias will trust nothing else in the response.
 */
public record OdResponse(
        @JsonProperty("from") Instant from,
        @JsonProperty("to") Instant to,
        @JsonProperty("zones") List<String> zones,
        @JsonProperty("matrix") List<Cell> matrix,
        @JsonProperty("coverage") Coverage coverage
) {
    public record Cell(
            @JsonProperty("origin") String origin,
            @JsonProperty("destination") String destination,
            @JsonProperty("count") long count
    ) {}

    public record Coverage(
            @JsonProperty("vehicles_with_both_ends") long vehiclesWithBothEnds,
            @JsonProperty("vehicles_observed") long vehiclesObserved,
            @JsonProperty("coverage_fraction") Double coverageFraction,
            @JsonProperty("note") String note
    ) {
        public static final String NOTE =
                "Only vehicles observed at two or more cameras contribute to this matrix.";
    }
}
