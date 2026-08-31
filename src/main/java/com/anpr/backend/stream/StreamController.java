package com.anpr.backend.stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class StreamController {

    private final EventStream stream;

    public StreamController(EventStream stream) {
        this.stream = stream;
    }

    /** Live sightings and alerts. Historical events are never sent here. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = stream.register();
        if (emitter == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many open stream connections");
        }
        return emitter;
    }

    @GetMapping("/stream/status")
    public Map<String, Object> status() {
        return Map.of(
                "open_connections", stream.openConnections(),
                "events_pushed", stream.pushedCount(),
                "suppressed_as_historic", stream.suppressedAsHistoricCount(),
                "rejected_connections", stream.rejectedConnectionCount(),
                "muted", stream.isMuted());
    }
}
