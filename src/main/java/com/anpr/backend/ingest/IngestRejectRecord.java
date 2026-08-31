package com.anpr.backend.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;

/** A stored rejection, as returned by {@code GET /api/events/rejects}. */
public record IngestRejectRecord(
        @JsonProperty("id") long id,
        @JsonProperty("reason") String reason,
        @JsonProperty("received_at") Instant receivedAt,
        @JsonProperty("event_id") String eventId,
        @JsonRawValue @JsonProperty("raw_payload") String rawPayload
) {}
