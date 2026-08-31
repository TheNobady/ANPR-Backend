package com.anpr.backend.trajectory;

import com.anpr.backend.common.ApiProperties;
import com.anpr.backend.trajectory.dto.TrajectoryGeoJson;
import com.anpr.backend.trajectory.dto.TrajectoryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TrajectoryService {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryService.class);

    private final TrajectoryRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApiProperties properties;

    public TrajectoryService(TrajectoryRepository repository, JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper, ApiProperties properties) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** @return null when no such vehicle exists, so the controller can 404 */
    public TrajectoryResponse forVehicle(long vehicleId, Instant from, Instant to) {
        long began = System.nanoTime();

        // Fetched separately from the sightings because the summary must survive a range
        // that clips every sighting away, and because it is how a missing vehicle is detected.
        List<Map<String, Object>> header = jdbcTemplate.queryForList("""
                SELECT id, canonical_plate, status, sighting_count, first_seen_at, last_seen_at
                  FROM vehicles WHERE id = ?
                """, vehicleId);
        if (header.isEmpty()) {
            return null;
        }
        Map<String, Object> v = header.get(0);

        List<TrajectoryRow> rows = repository.findByVehicle(vehicleId, from, to);
        List<TrajectoryGeoJson.Feature> features = new ArrayList<>(Math.max(1, rows.size() * 2 - 1));

        // Which cameras this vehicle was actually seen at, and when -- used to decide whether
        // a camera on the route genuinely missed it.
        Map<String, List<Instant>> sightingsByCamera = rows.stream().collect(Collectors.groupingBy(
                TrajectoryRow::cameraId,
                Collectors.mapping(TrajectoryRow::firstSeenAt, Collectors.toList())));

        double totalDistance = 0.0;
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        Double minConfidence = null;

        for (TrajectoryRow row : rows) {
            if (row.hasHop()) {
                HopMetrics hop = hopMetrics(row, sightingsByCamera);
                features.add(TrajectoryGeoJson.Feature.of(row.hopGeoJson(), hop.properties()));
                if (hop.distanceM() != null) {
                    totalDistance += hop.distanceM();
                }
                if (row.linkConfidence() != null) {
                    confidenceSum += row.linkConfidence();
                    confidenceCount++;
                    minConfidence = minConfidence == null
                            ? row.linkConfidence() : Math.min(minConfidence, row.linkConfidence());
                }
            }
            features.add(TrajectoryGeoJson.Feature.of(row.cameraGeoJson(), sightingProperties(row)));
        }

        Long totalDuration = rows.size() < 2 ? null
                : Duration.between(rows.get(0).firstSeenAt(),
                        rows.get(rows.size() - 1).firstSeenAt()).toSeconds();

        TrajectoryResponse response = new TrajectoryResponse(
                vehicleId,
                (String) v.get("canonical_plate"),
                (String) v.get("status"),
                ((Number) v.getOrDefault("sighting_count", 0)).intValue(),
                instant((Timestamp) v.get("first_seen_at")),
                instant((Timestamp) v.get("last_seen_at")),
                minConfidence,
                confidenceCount == 0 ? null : confidenceSum / confidenceCount,
                features.isEmpty() ? null : totalDistance,
                totalDuration,
                TrajectoryGeoJson.of(features));

        log.debug("trajectory vehicle={} sightings={} features={} from={} to={} in {} ms",
                vehicleId, rows.size(), features.size(), from, to,
                (System.nanoTime() - began) / 1_000_000);
        return response;
    }

    private Map<String, Object> sightingProperties(TrajectoryRow row) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "sighting");
        p.put("sequence", row.sequenceNumber());
        p.put("observation_id", row.observationId());
        p.put("camera_id", row.cameraId());
        p.put("camera_name", row.cameraName());
        p.put("zone", row.zone());
        p.put("timestamp", row.firstSeenAt());
        p.put("dwell_ms", row.dwellMs());
        p.put("plate_read", row.plateText());
        p.put("plate_confidence", row.plateConfidence());
        p.put("vehicle_type", row.vehicleType());
        p.put("vehicle_colour", row.vehicleColour());
        p.put("snapshot_url", row.snapshotUrl());
        return p;
    }

    private record HopMetrics(Map<String, Object> properties, Double distanceM) {
    }

    private HopMetrics hopMetrics(TrajectoryRow row, Map<String, List<Instant>> sightingsByCamera) {
        boolean viaRoad = row.edgeId() != null;
        long durationS = Duration.between(row.previousFirstSeenAt(), row.firstSeenAt()).toSeconds();
        Double distance = viaRoad ? row.roadDistanceM() : row.straightLineDistanceM();

        List<String> skipped = skippedCameras(row, sightingsByCamera);

        Boolean detour = row.typicalSeconds() == null ? null
                : durationS > properties.getDetourRatio() * row.typicalSeconds();

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "hop");
        p.put("sequence", row.sequenceNumber());
        p.put("from_camera_id", row.fromCameraId());
        p.put("to_camera_id", row.cameraId());
        p.put("link_confidence", row.linkConfidence());
        p.put("score_breakdown", rawJson(row.scoreBreakdown()));
        p.put("duration_s", durationS);
        p.put("typical_s", row.typicalSeconds());
        p.put("distance_m", distance);
        // Always true: the road between two cameras was never observed, only its endpoints.
        // The response says so rather than letting the map imply the vehicle was tracked.
        p.put("inferred", true);
        p.put("geometry_source", viaRoad ? "road" : "straight_line");
        p.put("skipped_cameras", skipped);
        p.put("detour_suspected", detour);
        return new HopMetrics(p, distance);
    }

    /**
     * Cameras the edge's geometry passes that did not see this vehicle during the hop.
     *
     * <p>A camera that did see it would normally split the hop in two, so a non-empty list
     * means a working camera on the route missed the vehicle -- worth surfacing rather than
     * quietly dropping.
     */
    private List<String> skippedCameras(TrajectoryRow row, Map<String, List<Instant>> sightingsByCamera) {
        if (row.intermediateCameras() == null || row.intermediateCameras().length == 0) {
            return List.of();
        }
        Instant start = row.previousFirstSeenAt();
        Instant end = row.firstSeenAt();
        return Arrays.stream(row.intermediateCameras())
                .filter(cameraId -> sightingsByCamera.getOrDefault(cameraId, List.of()).stream()
                        .noneMatch(at -> at != null && !at.isBefore(start) && !at.isAfter(end)))
                .toList();
    }

    /** Splices stored JSONB through untouched; a malformed value becomes null rather than breaking the response. */
    private Object rawJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
