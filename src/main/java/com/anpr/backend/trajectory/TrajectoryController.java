package com.anpr.backend.trajectory;

import com.anpr.backend.common.ApiProperties;
import com.anpr.backend.identity.VehicleQueryService;
import com.anpr.backend.identity.dto.VehicleSearchResult;
import com.anpr.backend.trajectory.dto.PlateTrajectoryResponse;
import com.anpr.backend.trajectory.dto.TrajectoryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Plate-keyed trajectory lookup. The perception team's contract references this shape, so
 * it exists alongside the id-keyed endpoint rather than instead of it.
 */
@RestController
@RequestMapping("/api/trajectory")
public class TrajectoryController {

    /** Enough alternates for a disambiguation list without turning into a search results page. */
    private static final int MAX_ALTERNATES = 5;

    private final VehicleQueryService vehicles;
    private final TrajectoryService trajectories;
    private final ApiProperties properties;

    public TrajectoryController(VehicleQueryService vehicles, TrajectoryService trajectories,
                                ApiProperties properties) {
        this.vehicles = vehicles;
        this.trajectories = trajectories;
        this.properties = properties;
    }

    @GetMapping
    public PlateTrajectoryResponse byPlate(
            @RequestParam String plate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        List<VehicleSearchResult> matches = vehicles.search(
                plate, from, to, Math.min(MAX_ALTERNATES + 1, properties.getSearchMaxLimit()));

        if (matches.isEmpty()) {
            // Not a 404: "no vehicle carries that plate" is a legitimate search result, and a
            // frontend should show an empty state rather than an error.
            return new PlateTrajectoryResponse(plate, null, null, null, List.of());
        }

        VehicleSearchResult best = matches.get(0);
        TrajectoryResponse trajectory = trajectories.forVehicle(best.vehicleId(), from, to);

        List<PlateTrajectoryResponse.AlternateMatch> alternates = matches.stream()
                .skip(1)
                .limit(MAX_ALTERNATES)
                .map(m -> new PlateTrajectoryResponse.AlternateMatch(
                        m.vehicleId(), m.canonicalPlate(), m.matchType(),
                        m.sightingCount(), m.lastSeenAt()))
                .toList();

        return new PlateTrajectoryResponse(
                plate, best.canonicalPlate(), best.matchType(), trajectory, alternates);
    }
}
