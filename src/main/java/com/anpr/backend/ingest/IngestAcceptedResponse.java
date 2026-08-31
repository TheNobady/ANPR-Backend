package com.anpr.backend.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Success envelope: {"status":"received","event_id":"..."} */
public record IngestAcceptedResponse(
        @JsonProperty("status") String status,
        @JsonProperty("event_id") String eventId
) {
    public static IngestAcceptedResponse of(String eventId) {
        return new IngestAcceptedResponse("received", eventId);
    }
}
