package com.anpr.backend.sim;

import com.anpr.backend.camera.Camera;
import com.anpr.backend.sim.dto.BackfillRequest;
import com.anpr.backend.sim.dto.BackfillResponse;
import com.anpr.backend.sim.dto.ScenarioRequest;
import com.anpr.backend.sim.dto.ScenarioResponse;
import com.anpr.backend.sim.dto.SimStartRequest;
import com.anpr.backend.sim.dto.SimStatusResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Drives the traffic simulation.
 *
 * <p>Two modes, deliberately different:
 *
 * <ul>
 *   <li><b>Live</b> -- vehicles move in real time. A 200-second hop fires 200 seconds
 *       later and timestamps are wall-clock now. Time is never compressed: a speed
 *       multiplier would make measured gaps disagree with {@code t_typical_s}, and every
 *       congestion calculation downstream is built on that agreement.</li>
 *   <li><b>Backfill</b> -- a block of history generated as fast as the ingest endpoint
 *       accepts it, with timestamps in the past. This is why the multiplier isn't
 *       needed.</li>
 * </ul>
 */
@Service
@Profile("sim")
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private static final int MIN_HOPS = 3;
    private static final int MAX_HOPS = 6;
    private static final double TRAVEL_TIME_SIGMA = 0.25;

    /** Enough to keep ingest busy without starving its own connection pool. */
    private static final int BACKFILL_THREADS = 8;

    private final SimCameraGraph graph;
    private final VehicleFactory vehicleFactory;
    private final SimEventFactory eventFactory;
    private final IngestHttpClient ingestClient;
    private final SimGroundTruthStore groundTruth;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            4, runnable -> {
                Thread t = new Thread(runnable, "sim-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final AtomicLong vehiclesSpawned = new AtomicLong();
    private final AtomicLong eventsSent = new AtomicLong();
    private final AtomicLong eventsRejected = new AtomicLong();
    private final AtomicInteger scenarioPending = new AtomicInteger();

    private volatile boolean running;
    private volatile Instant startedAt;
    private volatile double vehiclesPerMinute = 6.0;
    private volatile double ocrErrorRate = 0.15;
    private volatile double nullPlateRate = 0.05;
    private volatile double embeddingNoiseSigma = 0.05;
    private volatile double congestionFactor = 1.0;
    private volatile ScheduledFuture<?> spawnTask;
    private volatile ScheduledFuture<?> stopTask;

    public SimulationEngine(SimCameraGraph graph,
                            VehicleFactory vehicleFactory,
                            SimEventFactory eventFactory,
                            IngestHttpClient ingestClient,
                            SimGroundTruthStore groundTruth) {
        this.graph = graph;
        this.vehicleFactory = vehicleFactory;
        this.eventFactory = eventFactory;
        this.ingestClient = ingestClient;
        this.groundTruth = groundTruth;
    }

    // ------------------------------------------------------------------ live mode

    public synchronized SimStatusResponse start(SimStartRequest request) {
        applyRates(request.ocrErrorRate(), request.nullPlateRate(), request.embeddingNoiseSigma(),
                request.congestionFactor());
        this.vehiclesPerMinute = request.vehiclesPerMinuteOrDefault();

        if (running) {
            // Rates and spawn rate are adjustable while traffic is flowing; no restart.
            log.info("simulator: already running, updated rates to {} veh/min, ocr={}, nullPlate={}",
                    vehiclesPerMinute, ocrErrorRate, nullPlateRate);
            rescheduleSpawns();
            return status();
        }

        graph.reload();
        if (graph.isEmpty()) {
            throw new IllegalStateException(
                    "camera_edges is empty -- generate the graph before starting the simulator");
        }

        running = true;
        startedAt = Instant.now();
        rescheduleSpawns();

        int durationMinutes = request.durationMinutesOrDefault();
        if (durationMinutes > 0) {
            stopTask = scheduler.schedule(this::stop, durationMinutes, TimeUnit.MINUTES);
        }
        log.info("simulator: started at {} veh/min (ocr={}, nullPlate={}, duration={})",
                vehiclesPerMinute, ocrErrorRate, nullPlateRate,
                durationMinutes == 0 ? "until stopped" : durationMinutes + "m");
        return status();
    }

    private void rescheduleSpawns() {
        if (spawnTask != null) {
            spawnTask.cancel(false);
        }
        long periodMs = Math.max(50, Math.round(60_000.0 / vehiclesPerMinute));
        spawnTask = scheduler.scheduleAtFixedRate(
                this::spawnOne, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    public synchronized SimStatusResponse stop() {
        if (running) {
            log.info("simulator: stopping after {} vehicles, {} events ({} rejected)",
                    vehiclesSpawned.get(), eventsSent.get(), eventsRejected.get());
        }
        running = false;
        if (spawnTask != null) {
            spawnTask.cancel(false);
            spawnTask = null;
        }
        if (stopTask != null) {
            stopTask.cancel(false);
            stopTask = null;
        }
        return status();
    }

    /** One vehicle enters the network and schedules its own journey. */
    private void spawnOne() {
        if (!running) {
            return;
        }
        try {
            RandomGenerator random = ThreadLocalRandom.current();
            String start = graph.randomStart(random);
            if (start == null) {
                return;
            }
            SimVehicle vehicle = vehicleFactory.newVehicle(random);
            List<SimCameraGraph.Hop> route = graph.randomRoute(start, MIN_HOPS, MAX_HOPS, random);
            vehiclesSpawned.incrementAndGet();

            // The first sighting is now; each later one is delayed by the real travel time.
            emitLive(vehicle, start);
            long cumulativeMs = 0;
            for (SimCameraGraph.Hop hop : route) {
                cumulativeMs += sampleTravelSeconds(hop, random) * 1000L;
                final String cameraId = hop.toCameraId();
                scheduler.schedule(() -> emitLive(vehicle, cameraId), cumulativeMs, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("simulator: spawn failed", e);
        }
    }

    /** A live sighting is dropped if the simulation was stopped while it was in flight. */
    private void emitLive(SimVehicle vehicle, String cameraId) {
        if (running) {
            send(vehicle, cameraId, Instant.now(), false);
        }
    }

    // ------------------------------------------------------------------- backfill

    /**
     * Generates {@code hours} of history ending now and posts it immediately. Journeys are
     * placed so the whole route -- not just its first sighting -- lands inside the window.
     */
    public BackfillResponse backfill(BackfillRequest request) {
        applyRates(request.ocrErrorRate(), request.nullPlateRate(), request.embeddingNoiseSigma(),
                request.congestionFactor());
        graph.reload();
        if (graph.isEmpty()) {
            throw new IllegalStateException(
                    "camera_edges is empty -- generate the graph before running a backfill");
        }

        double hours = request.hoursOrDefault();
        int vehiclesPerHour = request.vehiclesPerHourOrDefault();
        int vehicleCount = (int) Math.round(hours * vehiclesPerHour);

        Instant windowEnd = Instant.now();
        Instant windowStart = windowEnd.minusSeconds((long) (hours * 3600));
        long windowSeconds = Math.max(1, windowEnd.getEpochSecond() - windowStart.getEpochSecond());

        long began = System.nanoTime();
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // Bounded queue plus caller-runs: the generator cannot outrun the posters and
        // pile a whole backfill into memory.
        ThreadPoolExecutor posters = new ThreadPoolExecutor(
                BACKFILL_THREADS, BACKFILL_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BACKFILL_THREADS * 32),
                new ThreadPoolExecutor.CallerRunsPolicy());

        try {
            for (int i = 0; i < vehicleCount; i++) {
                RandomGenerator random = ThreadLocalRandom.current();
                String start = graph.randomStart(random);
                if (start == null) {
                    break;
                }
                SimVehicle vehicle = vehicleFactory.newVehicle(random);
                List<SimCameraGraph.Hop> route = graph.randomRoute(start, MIN_HOPS, MAX_HOPS, random);

                List<Integer> travelSeconds = route.stream()
                        .map(hop -> sampleTravelSeconds(hop, random)).toList();
                long journeySeconds = travelSeconds.stream().mapToLong(Integer::longValue).sum();

                // Nothing may land in the future. A journey that fits starts somewhere
                // inside the window; one longer than the window was already under way
                // when the window opened, so it is anchored to end exactly at it.
                Instant at = journeySeconds >= windowSeconds
                        ? windowEnd.minusSeconds(journeySeconds)
                        : windowStart.plusSeconds(random.nextLong(windowSeconds - journeySeconds + 1));

                vehiclesSpawned.incrementAndGet();
                submit(posters, vehicle, start, at, false, sent, rejected);

                Instant cursor = at;
                for (int h = 0; h < route.size(); h++) {
                    cursor = cursor.plusSeconds(travelSeconds.get(h));
                    submit(posters, vehicle, route.get(h).toCameraId(), cursor, false, sent, rejected);
                }
            }
        } finally {
            posters.shutdown();
            try {
                posters.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long elapsedMs = (System.nanoTime() - began) / 1_000_000;
        log.info("simulator: backfill of {}h produced {} events from {} vehicles ({} rejected) in {} ms",
                hours, sent.get(), vehicleCount, rejected.get(), elapsedMs);

        return new BackfillResponse("complete", hours, vehicleCount, sent.get(), rejected.get(),
                windowStart, windowEnd, elapsedMs);
    }

    private void submit(ExecutorService posters, SimVehicle vehicle, String cameraId, Instant at,
                        boolean forceNullPlate, AtomicInteger sent, AtomicInteger rejected) {
        posters.execute(() -> {
            if (send(vehicle, cameraId, at, forceNullPlate)) {
                sent.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        });
    }

    // ------------------------------------------------------------------ scenarios

    /** Scripted vehicles, scheduled on top of whatever background traffic is running. */
    public ScenarioResponse runScenario(ScenarioRequest request) {
        graph.reload();
        List<String> warnings = new ArrayList<>();
        if (request == null || request.vehicles() == null || request.vehicles().isEmpty()) {
            return new ScenarioResponse("empty", 0, List.of("no vehicles in scenario"));
        }

        int scheduled = 0;
        for (ScenarioRequest.ScenarioVehicle spec : request.vehicles()) {
            if (spec.route() == null || spec.route().size() < 2) {
                warnings.add("skipped %s: route needs at least two cameras".formatted(spec.plate()));
                continue;
            }
            List<String> unknown = spec.route().stream()
                    .filter(id -> graph.camera(id) == null).toList();
            if (!unknown.isEmpty()) {
                warnings.add("skipped %s: unknown cameras %s".formatted(spec.plate(), unknown));
                continue;
            }

            RandomGenerator random = ThreadLocalRandom.current();
            SimVehicle vehicle = new SimVehicle(
                    vehicleFactory.nextVehicleId(),
                    spec.plate() == null ? vehicleFactory.randomPlate(random) : spec.plate(),
                    spec.type() == null ? "car" : spec.type(),
                    spec.colour() == null ? "white" : spec.colour(),
                    spec.embeddingSeed() == null
                            ? vehicleFactory.unitEmbedding(random)
                            : vehicleFactory.seededEmbedding(spec.embeddingSeed()));
            Set<String> forceNullAt = spec.forceNullPlateAt() == null
                    ? Set.of() : Set.copyOf(spec.forceNullPlateAt());

            long delayMs = Math.max(0, spec.startDelaySeconds() == null ? 0 : spec.startDelaySeconds()) * 1000L;
            List<String> route = spec.route();

            for (int i = 0; i < route.size(); i++) {
                String cameraId = route.get(i);
                if (i > 0) {
                    SimCameraGraph.Hop hop = graph.hop(route.get(i - 1), cameraId);
                    if (hop == null) {
                        // Still run it: a scripted route is an instruction, not a query.
                        warnings.add("%s: no edge %s -> %s, using a %ds default"
                                .formatted(vehicle.plate(), route.get(i - 1), cameraId, 120));
                        delayMs += 120_000L;
                    } else {
                        delayMs += sampleTravelSeconds(hop, random) * 1000L;
                    }
                }
                boolean forceNull = forceNullAt.contains(cameraId);
                scenarioPending.incrementAndGet();
                scheduler.schedule(() -> {
                    try {
                        send(vehicle, cameraId, Instant.now(), forceNull);
                    } finally {
                        scenarioPending.decrementAndGet();
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            }
            vehiclesSpawned.incrementAndGet();
            scheduled++;
            log.info("simulator: scenario vehicle {} scheduled over {} cameras, plate dropped at {}",
                    vehicle.plate(), route.size(), forceNullAt.isEmpty() ? "nowhere" : forceNullAt);
        }
        return new ScenarioResponse("scheduled", scheduled, warnings);
    }

    // --------------------------------------------------------------------- shared

    private boolean send(SimVehicle vehicle, String cameraId, Instant firstSeen, boolean forceNullPlate) {
        Camera camera = graph.camera(cameraId);
        if (camera == null) {
            log.warn("simulator: no such camera {}", cameraId);
            return false;
        }
        SimEventPayload payload = eventFactory.build(
                vehicle, camera, firstSeen, ocrErrorRate, nullPlateRate, embeddingNoiseSigma,
                forceNullPlate, ThreadLocalRandom.current());

        boolean accepted = ingestClient.send(payload);
        if (accepted) {
            groundTruth.record(payload.eventId(), vehicle.simVehicleId(), vehicle.plate());
            eventsSent.incrementAndGet();
        } else {
            eventsRejected.incrementAndGet();
        }
        return accepted;
    }

    /**
     * Log-normal around {@code t_typical_s}, clamped to the edge's window. Real travel
     * times are right-skewed -- a red light costs you, a green one saves you less.
     */
    private int sampleTravelSeconds(SimCameraGraph.Hop hop, RandomGenerator random) {
        double sampled = hop.typicalS() * Math.exp(random.nextGaussian() * TRAVEL_TIME_SIGMA)
                * edgeCongestion(hop);
        // Congestion has to be allowed past t_max, or the clamp would erase exactly the
        // delays the congestion analytics exists to show.
        double ceiling = congestionFactor > 1.0
                ? hop.maxS() * congestionFactor : hop.maxS();
        return (int) Math.round(Math.clamp(sampled, hop.minS(), ceiling));
    }

    /**
     * Spreads the slowdown unevenly over the network using a stable hash of the edge, so
     * some corridors jam and others stay clear. A uniform multiplier would move every
     * congestion ratio together and produce a map with exactly one colour on it.
     */
    private double edgeCongestion(SimCameraGraph.Hop hop) {
        if (congestionFactor <= 1.0) {
            return 1.0;
        }
        int hash = (hop.toCameraId() + ':' + hop.typicalS()).hashCode();
        double position = ((hash >>> 8) % 1000) / 1000.0;
        return 1.0 + (congestionFactor - 1.0) * position;
    }

    private void applyRates(Double ocr, Double nullPlate, Double sigma, Double congestion) {
        if (congestion != null && congestion >= 1.0 && congestion <= 10.0) {
            this.congestionFactor = congestion;
        }
        if (ocr != null && ocr >= 0 && ocr <= 1) {
            this.ocrErrorRate = ocr;
        }
        if (nullPlate != null && nullPlate >= 0 && nullPlate <= 1) {
            this.nullPlateRate = nullPlate;
        }
        if (sigma != null && sigma >= 0 && sigma <= 5) {
            this.embeddingNoiseSigma = sigma;
        }
    }

    public SimStatusResponse status() {
        Instant started = startedAt;
        return new SimStatusResponse(
                running,
                vehiclesSpawned.get(),
                eventsSent.get(),
                eventsRejected.get(),
                started,
                started == null ? 0 : Duration.between(started, Instant.now()).toSeconds(),
                vehiclesPerMinute,
                ocrErrorRate,
                nullPlateRate,
                embeddingNoiseSigma,
                congestionFactor,
                scenarioPending.get(),
                ingestClient.ingestUrl());
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
    }
}
