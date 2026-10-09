package com.anpr.backend.sim;

import com.anpr.backend.camera.Camera;
import com.anpr.backend.camera.CameraService;
import com.anpr.backend.graph.CameraEdge;
import com.anpr.backend.graph.CameraEdgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * The Phase 2a camera graph, held in memory for the simulator to walk.
 *
 * <p>Loaded once and reused; {@link #reload()} picks up a regenerated graph without a
 * restart. Routing here is a plain random walk over real edges, so every journey the
 * simulator emits is one a vehicle could actually drive.
 */
@Component
public class SimCameraGraph {

    private static final Logger log = LoggerFactory.getLogger(SimCameraGraph.class);

    private final CameraEdgeRepository edgeRepository;
    private final CameraService cameraService;

    private volatile Map<String, List<Hop>> adjacency = Map.of();
    private volatile Map<String, Camera> cameras = Map.of();
    private volatile List<String> startCandidates = List.of();

    public SimCameraGraph(CameraEdgeRepository edgeRepository, CameraService cameraService) {
        this.edgeRepository = edgeRepository;
        this.cameraService = cameraService;
    }

    /** One outbound edge, with the travel-time window Phase 2a computed for it. */
    public record Hop(String toCameraId, int typicalS, int minS, int maxS) {
    }

    public synchronized void reload() {
        Map<String, Camera> loadedCameras = new LinkedHashMap<>();
        cameraService.findActive().forEach(c -> loadedCameras.put(c.getCameraId(), c));

        // Only edges between cameras in the *active* set. Switching
        // CAMERA_SET deactivates the previous city's cameras but leaves
        // their edges in the table (observations reference them), so
        // walking every edge routes vehicles onto cameras that no longer
        // exist -- every such event is then dropped at send time. That
        // silently cost 36% of a backfill before this filter.
        Map<String, List<Hop>> loadedAdjacency = new LinkedHashMap<>();
        int skipped = 0;
        for (CameraEdge edge : edgeRepository.findAll()) {
            if (!loadedCameras.containsKey(edge.getFromCameraId())
                    || !loadedCameras.containsKey(edge.getToCameraId())) {
                skipped++;
                continue;
            }
            loadedAdjacency
                    .computeIfAbsent(edge.getFromCameraId(), k -> new ArrayList<>())
                    .add(new Hop(edge.getToCameraId(), edge.getTTypicalS(),
                            edge.getTMinS(), edge.getTMaxS()));
        }

        this.cameras = Collections.unmodifiableMap(loadedCameras);
        this.adjacency = Collections.unmodifiableMap(loadedAdjacency);
        // Starting a journey at a dead end would emit a single lonely event.
        this.startCandidates = loadedAdjacency.keySet().stream().toList();

        log.info("simulator: loaded {} cameras and {} edges from {} origins"
                        + " ({} edges skipped as outside the active camera set)",
                loadedCameras.size(),
                loadedAdjacency.values().stream().mapToInt(List::size).sum(),
                loadedAdjacency.size(), skipped);
    }

    public boolean isEmpty() {
        return adjacency.isEmpty();
    }

    public Camera camera(String cameraId) {
        return cameras.get(cameraId);
    }

    public List<Hop> outbound(String cameraId) {
        return adjacency.getOrDefault(cameraId, List.of());
    }

    /** The edge between two named cameras, when a scripted scenario asks for a specific leg. */
    public Hop hop(String fromCameraId, String toCameraId) {
        return outbound(fromCameraId).stream()
                .filter(h -> h.toCameraId().equals(toCameraId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Walks {@code minHops}..{@code maxHops} outbound edges from a random start,
     * preferring cameras not yet visited so a journey crosses the city instead of
     * bouncing between two junctions. Stops early at a dead end.
     */
    public List<Hop> randomRoute(String startCameraId, int minHops, int maxHops, RandomGenerator random) {
        int target = minHops + random.nextInt(maxHops - minHops + 1);
        List<Hop> route = new ArrayList<>(target);
        List<String> visited = new ArrayList<>();
        visited.add(startCameraId);

        String current = startCameraId;
        for (int i = 0; i < target; i++) {
            List<Hop> options = outbound(current);
            if (options.isEmpty()) {
                break;
            }
            List<Hop> fresh = options.stream().filter(h -> !visited.contains(h.toCameraId())).toList();
            List<Hop> pool = fresh.isEmpty() ? options : fresh;
            Hop chosen = pool.get(random.nextInt(pool.size()));
            route.add(chosen);
            visited.add(chosen.toCameraId());
            current = chosen.toCameraId();
        }
        return route;
    }

    public String randomStart(RandomGenerator random) {
        List<String> candidates = startCandidates;
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }
}
