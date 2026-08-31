package com.anpr.backend.ingest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class IngestController {

    private static final int MAX_REJECT_LIMIT = 500;

    private final IngestService ingestService;
    private final IngestRejectStore rejectStore;

    public IngestController(IngestService ingestService, IngestRejectStore rejectStore) {
        this.ingestService = ingestService;
        this.rejectStore = rejectStore;
    }

    /**
     * The node-facing ingest endpoint.
     *
     * <p>The body is taken as a {@code String} rather than a bound DTO on purpose: a
     * payload that fails to parse still has to reach {@code ingest_rejects} verbatim,
     * which is impossible once Spring's own binding has rejected the request.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestAcceptedResponse ingest(@RequestBody(required = false) String rawBody) {
        return ingestService.ingest(rawBody);
    }

    /** Debug: the most recent rejections and why they were rejected. */
    @GetMapping("/rejects")
    public List<IngestRejectRecord> rejects(@RequestParam(defaultValue = "50") int limit) {
        return rejectStore.findRecent(Math.clamp(limit, 1, MAX_REJECT_LIMIT));
    }
}
