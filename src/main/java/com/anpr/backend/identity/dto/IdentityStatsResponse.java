package com.anpr.backend.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record IdentityStatsResponse(
        @JsonProperty("vehicles_by_status") Map<String, Long> vehiclesByStatus,
        @JsonProperty("vehicle_count") long vehicleCount,
        @JsonProperty("mean_sightings_per_vehicle") double meanSightingsPerVehicle,
        @JsonProperty("links_made") long linksMade,
        @JsonProperty("unlinked_observations") long unlinkedObservations,
        @JsonProperty("live_queue_depth") int liveQueueDepth,
        @JsonProperty("live_processed") long liveProcessed,
        @JsonProperty("live_dropped") long liveDropped
) {}
