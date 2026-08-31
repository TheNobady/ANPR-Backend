package com.anpr.backend.analytics;

import com.anpr.backend.analytics.dto.CongestionResponse;
import com.anpr.backend.analytics.dto.DensityResponse;
import com.anpr.backend.analytics.dto.HeatmapResponse;
import com.anpr.backend.analytics.dto.OdResponse;
import com.anpr.backend.analytics.dto.SummaryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * Every endpoint takes an optional {@code from}/{@code to} range and defaults to the last
 * {@code analytics.default-window-minutes}. That default is what makes live mode and timeline
 * scrubbing the same code path: "live" is the frontend calling with no range on a timer.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final int MAX_CONGESTION_ROWS = 200;

    private final AnalyticsQueryService analytics;
    private final RollupService rollups;
    private final AnalyticsProperties properties;

    public AnalyticsController(AnalyticsQueryService analytics, RollupService rollups,
                               AnalyticsProperties properties) {
        this.analytics = analytics;
        this.rollups = rollups;
        this.properties = properties;
    }

    @GetMapping("/heatmap")
    public HeatmapResponse heatmap(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant[] range = range(from, to);
        return analytics.heatmap(range[0], range[1]);
    }

    @GetMapping("/congestion")
    public CongestionResponse congestion(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "20") int limit) {
        Instant[] range = range(from, to);
        return analytics.congestion(range[0], range[1], Math.clamp(limit, 1, MAX_CONGESTION_ROWS));
    }

    @GetMapping("/density")
    public DensityResponse density(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "camera_id", required = false) String cameraId) {
        Instant[] range = range(from, to);
        return analytics.density(range[0], range[1], cameraId);
    }

    @GetMapping("/od")
    public OdResponse od(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant[] range = range(from, to);
        return analytics.od(range[0], range[1]);
    }

    @GetMapping("/summary")
    public SummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant[] range = range(from, to);
        return analytics.summary(range[0], range[1]);
    }

    /**
     * Rebuilds the aggregates for a range from scratch. Needed after any backfill or identity
     * rebuild, both of which change the trajectories the rollups are derived from.
     */
    @PostMapping("/recompute")
    public RollupService.RollupSummary recompute(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant[] range = range(from, to);
        return rollups.recompute(range[0], range[1]);
    }

    private Instant[] range(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null
                ? end.minus(Duration.ofMinutes(properties.getDefaultWindowMinutes()))
                : from;
        return new Instant[]{start, end};
    }
}
