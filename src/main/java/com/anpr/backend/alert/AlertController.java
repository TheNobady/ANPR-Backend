package com.anpr.backend.alert;

import com.anpr.backend.alert.dto.AlertDetail;
import com.anpr.backend.alert.dto.AlertStats;
import com.anpr.backend.alert.dto.AlertSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private static final int MAX_LIMIT = 500;

    private final AlertQueryService alerts;

    public AlertController(AlertQueryService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public List<AlertSummary> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "vehicle_id", required = false) Long vehicleId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return alerts.find(type, severity, from, to, vehicleId,
                Math.clamp(limit, 1, MAX_LIMIT), Math.max(0, offset));
    }

    /** Placed before {@code /{id}} so "stats" is not parsed as an id. */
    @GetMapping("/stats")
    public AlertStats stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return alerts.stats(from, to);
    }

    @GetMapping("/{id}")
    public AlertDetail detail(@PathVariable long id) {
        AlertDetail detail = alerts.detail(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No alert with id " + id);
        }
        return detail;
    }
}
