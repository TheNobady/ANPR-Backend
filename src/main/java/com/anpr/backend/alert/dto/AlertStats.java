package com.anpr.backend.alert.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

public record AlertStats(
        @JsonProperty("total") long total,
        @JsonProperty("by_type") Map<String, Long> byType,
        @JsonProperty("by_severity") Map<String, Long> bySeverity,
        @JsonProperty("from") Instant from,
        @JsonProperty("to") Instant to
) {}
