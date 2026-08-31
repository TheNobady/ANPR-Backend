package com.anpr.backend.identity;

import com.anpr.backend.common.ApiProperties;
import com.anpr.backend.identity.dto.VehicleDetailResponse;
import com.anpr.backend.identity.dto.VehicleSearchResult;
import com.anpr.backend.trajectory.TrajectoryService;
import com.anpr.backend.trajectory.dto.TrajectoryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleQueryService vehicles;
    private final TrajectoryService trajectories;
    private final ApiProperties properties;

    public VehicleController(VehicleQueryService vehicles, TrajectoryService trajectories,
                             ApiProperties properties) {
        this.vehicles = vehicles;
        this.trajectories = trajectories;
        this.properties = properties;
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleDetailResponse> byId(@PathVariable long id) {
        VehicleDetailResponse vehicle = vehicles.findById(id);
        return vehicle == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(vehicle);
    }

    /**
     * The vehicle's route as GeoJSON, optionally clipped to a time range.
     *
     * <p>A range that clips every sighting away still returns the summary with an empty
     * FeatureCollection -- "this vehicle was not seen in that window" is an answer, not an
     * error. An unknown vehicle id is a 404.
     */
    @GetMapping("/{id}/trajectory")
    public TrajectoryResponse trajectory(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        TrajectoryResponse trajectory = trajectories.forVehicle(id, from, to);
        if (trajectory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No vehicle with id " + id);
        }
        return trajectory;
    }

    @GetMapping("/search")
    public List<VehicleSearchResult> search(
            @RequestParam(name = "plate") String plate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit) {
        int effective = limit == null ? properties.getSearchLimit() : limit;
        return vehicles.search(plate, from, to,
                Math.clamp(effective, 1, properties.getSearchMaxLimit()));
    }
}
