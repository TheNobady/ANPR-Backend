package com.anpr.backend.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Answer to "is anything actually arriving?". */
public record SystemHealthResponse(
        @JsonProperty("camera_count") long cameraCount,
        @JsonProperty("active_camera_count") long activeCameraCount,
        @JsonProperty("observation_count") long observationCount,
        @JsonProperty("last_ingest_at") Instant lastIngestAt,
        @JsonProperty("reject_count") long rejectCount,
        /** Counts of failures that were caught and swallowed; see FailureCounters. */
        @JsonProperty("failures") java.util.Map<String, Long> failures
) {}
