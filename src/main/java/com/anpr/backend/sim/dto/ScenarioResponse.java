package com.anpr.backend.sim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ScenarioResponse(
        @JsonProperty("status") String status,
        @JsonProperty("vehicles_scheduled") int vehiclesScheduled,
        @JsonProperty("warnings") List<String> warnings
) {}
