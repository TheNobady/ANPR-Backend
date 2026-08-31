package com.anpr.backend.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RebuildResponse(
        @JsonProperty("status") String status,
        @JsonProperty("observations_processed") long observationsProcessed,
        @JsonProperty("vehicles_created") long vehiclesCreated,
        @JsonProperty("links_made") long linksMade,
        @JsonProperty("unlinked_observations") long unlinkedObservations,
        @JsonProperty("elapsed_ms") long elapsedMs,
        /**
         * A rebuild rewrites the trajectories analytics is derived from, so the aggregates
         * for the range it touched are recomputed with it. Reported here so a caller can see
         * it happened rather than having to remember to do it.
         */
        @JsonProperty("analytics_recomputed") AnalyticsRecomputed analyticsRecomputed
) {
    public record AnalyticsRecomputed(
            @JsonProperty("from") java.time.Instant from,
            @JsonProperty("to") java.time.Instant to,
            @JsonProperty("density_rows") int densityRows,
            @JsonProperty("edge_rows") int edgeRows,
            @JsonProperty("od_rows") int odRows,
            @JsonProperty("elapsed_ms") long elapsedMs
    ) {}
}
