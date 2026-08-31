package com.anpr.backend.sim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SimStatusResponse(
        @JsonProperty("running") boolean running,
        @JsonProperty("vehicles_spawned") long vehiclesSpawned,
        @JsonProperty("events_sent") long eventsSent,
        @JsonProperty("events_rejected") long eventsRejected,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("uptime_seconds") long uptimeSeconds,
        @JsonProperty("vehicles_per_minute") double vehiclesPerMinute,
        @JsonProperty("ocr_error_rate") double ocrErrorRate,
        @JsonProperty("null_plate_rate") double nullPlateRate,
        @JsonProperty("embedding_noise_sigma") double embeddingNoiseSigma,
        @JsonProperty("congestion_factor") double congestionFactor,
        @JsonProperty("scenario_vehicles_pending") int scenarioVehiclesPending,
        @JsonProperty("ingest_url") String ingestUrl
) {}
