package com.anpr.backend.identity;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live resolution path.
 *
 * <p>Single-threaded on purpose. Resolution reads a vehicle's state and then mutates it;
 * two threads processing observations for the same vehicle would race on
 * {@code last_seen_at} and the centroid, and the greedy linking is order-dependent anyway.
 *
 * <p>Ingest offers into this queue and moves on. The offer is non-blocking and a full
 * queue drops the id with a warning: identity resolution is downstream of ingestion and
 * must never be able to slow it down or fail it. Anything dropped is recovered by
 * {@code POST /api/identity/rebuild}.
 */
@Component
public class IdentityQueue {

    private static final Logger log = LoggerFactory.getLogger(IdentityQueue.class);

    private static final int CAPACITY = 10_000;

    private final BlockingQueue<Long> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Held while an observation is being resolved, and by a rebuild for its whole run.
     * {@link #pause()} alone is not enough: it stops new work being picked up, but a
     * resolution already in flight would keep writing, and a trajectory_event inserted
     * between the rebuild's two DELETEs trips the foreign key back to vehicles.
     */
    private final Object mutex = new Object();

    private final IdentityPipeline pipeline;
    private volatile Thread consumer;
    private volatile boolean draining = true;
    /** Held off while a rebuild owns the tables, so the two paths never interleave. */
    private volatile boolean paused;

    public IdentityQueue(IdentityPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostConstruct
    void start() {
        consumer = new Thread(this::drain, "identity-resolver");
        consumer.setDaemon(true);
        consumer.start();
    }

    /** Never blocks, never throws. Called from the ingest request thread. */
    public void submit(long observationId) {
        if (!queue.offer(observationId)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("identity: queue full, dropped observation {} ({} dropped so far) -- "
                        + "run POST /api/identity/rebuild to recover", observationId, n);
            }
        }
    }

    private void drain() {
        while (draining) {
            try {
                Long observationId = queue.poll(200, TimeUnit.MILLISECONDS);
                if (observationId == null) {
                    continue;
                }
                synchronized (mutex) {
                    if (paused) {
                        continue;   // a rebuild is rewriting these tables; it will cover this row
                    }
                    pipeline.process(observationId);
                }
                processed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("identity: failed to resolve an observation", e);
            }
        }
    }

    /**
     * Runs {@code work} with the live consumer held off and any in-flight resolution
     * already finished, so it has the vehicle and trajectory tables to itself.
     */
    public <T> T runExclusively(java.util.function.Supplier<T> work) {
        pause();
        try {
            synchronized (mutex) {
                return work.get();
            }
        } finally {
            resume();
        }
    }

    private void pause() {
        paused = true;
        queue.clear();
    }

    private void resume() {
        paused = false;
    }

    public long processed() {
        return processed.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public int depth() {
        return queue.size();
    }

    @PreDestroy
    void stop() {
        draining = false;
        if (consumer != null) {
            consumer.interrupt();
        }
    }
}
