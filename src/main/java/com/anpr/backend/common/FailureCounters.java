package com.anpr.backend.common;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts for things that were caught and swallowed.
 *
 * <p>Several components deliberately catch everything, because a failure there must not take
 * down something more important -- alerting must not break identity resolution, streaming
 * must not break ingestion. That is the right trade, but a swallowed failure with no counter
 * is invisible: a broken query in the alert writer produced zero alerts for a whole phase and
 * looked exactly like "nothing matched". Every such catch increments a counter here, logs at
 * ERROR with the exception, and the counters appear on {@code GET /api/health/system}.
 */
@Component
public class FailureCounters {

    public static final String ALERT_WRITE = "alert_write_failures";
    public static final String ALERT_EVALUATION = "alert_evaluation_failures";
    public static final String STREAM_PUSH = "stream_push_failures";
    public static final String IDENTITY_POST_PROCESSING = "identity_post_processing_failures";
    public static final String ROLLUP = "analytics_rollup_failures";

    private static final List<String> KNOWN = List.of(
            ALERT_WRITE, ALERT_EVALUATION, STREAM_PUSH, IDENTITY_POST_PROCESSING, ROLLUP);

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    public long get(String name) {
        AtomicLong counter = counters.get(name);
        return counter == null ? 0L : counter.get();
    }

    /** Always reports every known counter, so a zero is visibly a zero rather than a missing key. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        KNOWN.forEach(name -> out.put(name, get(name)));
        counters.keySet().stream().filter(name -> !out.containsKey(name)).sorted()
                .forEach(name -> out.put(name, get(name)));
        return out;
    }
}
