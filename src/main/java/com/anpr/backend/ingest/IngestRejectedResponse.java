package com.anpr.backend.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Rejection envelope: {"status":"rejected","event_id":"...","reason":"..."} */
public record IngestRejectedResponse(
        @JsonProperty("status") String status,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("reason") String reason
) {
    public static IngestRejectedResponse of(String eventId, String reason) {
        return new IngestRejectedResponse("rejected", eventId, reason);
    }
}
