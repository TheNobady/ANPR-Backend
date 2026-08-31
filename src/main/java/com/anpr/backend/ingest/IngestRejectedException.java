package com.anpr.backend.ingest;

/**
 * Thrown when a payload cannot be ingested. Carries the reason and the event id
 * (when one could be recovered from the raw body) for the 400 response.
 */
public class IngestRejectedException extends RuntimeException {

    private final String eventId;
    private final String reason;

    public IngestRejectedException(String eventId, String reason) {
        super(reason);
        this.eventId = eventId;
        this.reason = reason;
    }

    public String getEventId() {
        return eventId;
    }

    public String getReason() {
        return reason;
    }
}
