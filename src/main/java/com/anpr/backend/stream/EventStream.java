package com.anpr.backend.stream;

import com.anpr.backend.common.FailureCounters;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live Server-Sent Events fan-out.
 *
 * <p>Three rules keep this from becoming a liability:
 *
 * <ul>
 *   <li><b>Nothing here can break ingestion.</b> Every push is wrapped -- a client that
 *       vanished mid-write must not throw into the identity consumer that called it.</li>
 *   <li><b>Only live events go out.</b> An event older than {@code stream.live-window-seconds}
 *       is history; a two-hour backfill or a rebuild generates thousands of them and none
 *       belong on a live feed.</li>
 *   <li><b>Emitters are bounded and cleaned up.</b> Completion, timeout and error all remove
 *       the emitter, and connections past the cap are refused rather than leaked.</li>
 * </ul>
 */
@Component
public class EventStream {

    private static final Logger log = LoggerFactory.getLogger(EventStream.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final StreamProperties properties;
    private final ScheduledExecutorService heartbeats;

    /** Held during a rebuild: it replays history, and none of it is a live event. */
    private final AtomicBoolean muted = new AtomicBoolean(false);

    private final AtomicLong pushed = new AtomicLong();
    private final AtomicLong suppressedAsHistoric = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();

    private final FailureCounters failures;

    public EventStream(StreamProperties properties, FailureCounters failures) {
        this.properties = properties;
        this.failures = failures;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.heartbeats.scheduleAtFixedRate(this::heartbeat,
                properties.getHeartbeatSeconds(), properties.getHeartbeatSeconds(), TimeUnit.SECONDS);
    }

    /** @return null when the cap is reached, so the controller can answer 503 rather than leak */
    public SseEmitter register() {
        if (emitters.size() >= properties.getMaxEmitters()) {
            rejectedConnections.incrementAndGet();
            log.warn("stream: refusing connection, {} emitters already open", emitters.size());
            return null;
        }
        // No timeout: the container must not close a healthy feed out from under a viewer.
        // Cleanup is explicit on all three terminal callbacks instead.
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> remove(emitter, "completed"));
        emitter.onTimeout(() -> remove(emitter, "timeout"));
        emitter.onError(e -> remove(emitter, "error"));
        emitters.add(emitter);
        log.debug("stream: client connected, {} open", emitters.size());
        return emitter;
    }

    private void remove(SseEmitter emitter, String why) {
        if (emitters.remove(emitter)) {
            log.debug("stream: client removed ({}), {} open", why, emitters.size());
        }
    }

    /**
     * Pushes an event to every connected client, if it is live.
     *
     * @param occurredAt when the thing happened -- not when this was called
     */
    public void publish(String eventName, Object payload, Instant occurredAt) {
        if (muted.get()) {
            return;
        }
        if (occurredAt == null
                || Duration.between(occurredAt, Instant.now()).abs().getSeconds()
                   > properties.getLiveWindowSeconds()) {
            suppressedAsHistoric.incrementAndGet();
            return;
        }
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                // The client went away. Drop it and carry on -- the caller is the identity
                // consumer and must never see this.
                remove(emitter, "send failed");
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already dead
                }
            } catch (Exception e) {
                failures.increment(FailureCounters.STREAM_PUSH);
                log.error("stream: unexpected push failure", e);
                remove(emitter, "unexpected");
            }
        }
        pushed.incrementAndGet();
    }

    private void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                remove(emitter, "heartbeat failed");
            }
        }
    }

    /** Silences the stream while history is replayed. Always paired with {@link #unmute()}. */
    public void mute() {
        muted.set(true);
    }

    public void unmute() {
        muted.set(false);
    }

    public int openConnections() {
        return emitters.size();
    }

    public long pushedCount() {
        return pushed.get();
    }

    public long suppressedAsHistoricCount() {
        return suppressedAsHistoric.get();
    }

    public long rejectedConnectionCount() {
        return rejectedConnections.get();
    }

    public boolean isMuted() {
        return muted.get();
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
        emitters.forEach(e -> {
            try {
                e.complete();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        });
        emitters.clear();
    }
}
