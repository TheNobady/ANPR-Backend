package com.anpr.backend.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Structured error body for everything that is not an ingest rejection. */
public record ApiError(
        @JsonProperty("status") String status,
        @JsonProperty("error") String error,
        @JsonProperty("message") String message,
        @JsonProperty("path") String path,
        @JsonProperty("timestamp") Instant timestamp
) {
    public static ApiError of(String error, String message, String path) {
        return new ApiError("error", error, message, path, Instant.now());
    }
}
