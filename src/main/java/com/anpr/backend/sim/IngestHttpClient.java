package com.anpr.backend.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Posts simulated events to the real ingest endpoint over real HTTP.
 *
 * <p>Calling {@code IngestService} directly would be faster and completely useless: the
 * point is to exercise JSON serialisation, the controller, validation and the database
 * exactly as a perception node does. A non-200 here means the simulator has drifted from
 * the contract, so it is counted and logged rather than swallowed.
 */
@Component
public class IngestHttpClient {

    private static final Logger log = LoggerFactory.getLogger(IngestHttpClient.class);

    private final RestClient restClient;
    private final String ingestUrl;

    public IngestHttpClient(@Value("${sim.ingest-url:http://localhost:8000/api/events}") String ingestUrl) {
        this.ingestUrl = ingestUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // The nodes give up after 2s; a simulator that waits longer is not simulating them.
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String ingestUrl() {
        return ingestUrl;
    }

    /** @return true when the event was accepted */
    public boolean send(SimEventPayload payload) {
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(ingestUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    // Handle every status ourselves; a reject is data, not an exception.
                    .onStatus(status -> true, (request, res) -> { })
                    .toEntity(String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return true;
            }
            log.warn("simulator: ingest rejected event {} for camera {} -- HTTP {} {}",
                    payload.eventId(), payload.cameraId(),
                    response.getStatusCode().value(), response.getBody());
            return false;
        } catch (Exception e) {
            log.warn("simulator: ingest call failed for event {} -- {}", payload.eventId(), e.toString());
            return false;
        }
    }
}
