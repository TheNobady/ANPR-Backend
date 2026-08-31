package com.anpr.backend.sim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record BackfillResponse(
        @JsonProperty("status") String status,
        @JsonProperty("hours") double hours,
        @JsonProperty("vehicles") int vehicles,
        @JsonProperty("events_sent") int eventsSent,
        @JsonProperty("events_rejected") int eventsRejected,
        @JsonProperty("window_start") Instant windowStart,
        @JsonProperty("window_end") Instant windowEnd,
        @JsonProperty("elapsed_ms") long elapsedMs
) {}
