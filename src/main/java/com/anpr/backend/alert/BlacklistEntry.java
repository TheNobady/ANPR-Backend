package com.anpr.backend.alert;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record BlacklistEntry(
        @JsonProperty("id") long id,
        @JsonProperty("plate") String plate,
        @JsonProperty("plate_canonical") String plateCanonical,
        @JsonProperty("plate_skeleton") String plateSkeleton,
        @JsonProperty("reason") String reason,
        @JsonProperty("severity") String severity,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("created_at") Instant createdAt
) {}
