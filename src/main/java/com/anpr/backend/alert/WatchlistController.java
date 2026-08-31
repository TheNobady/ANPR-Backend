package com.anpr.backend.alert;

import com.anpr.backend.identity.PlateSkeleton;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Watchlist administration. No authentication yet -- Phase 7.
 *
 * <p>Every write reloads the in-memory cache, so an entry added here is in force for the
 * very next observation, with no restart.
 */
@RestController
@RequestMapping("/api")
public class WatchlistController {

    private final JdbcTemplate jdbcTemplate;
    private final WatchlistCache cache;

    public WatchlistController(JdbcTemplate jdbcTemplate, WatchlistCache cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
    }

    public record BlacklistRequest(
            @JsonProperty("plate") String plate,
            @JsonProperty("reason") String reason,
            @JsonProperty("severity") String severity
    ) {}

    public record RestrictedZoneRequest(
            @JsonProperty("zone") String zone,
            @JsonProperty("vehicle_types") List<String> vehicleTypes,
            @JsonProperty("active_from_hour") Integer activeFromHour,
            @JsonProperty("active_to_hour") Integer activeToHour,
            @JsonProperty("reason") String reason
    ) {}

    @GetMapping("/blacklist")
    public List<BlacklistEntry> blacklist() {
        return cache.allActive();
    }

    @PostMapping("/blacklist")
    public BlacklistEntry addToBlacklist(@RequestBody BlacklistRequest request) {
        if (request.plate() == null || request.plate().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plate is required");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        // Canonicalise and derive the skeleton on write, so the lookup path stays two map hits.
        String canonical = request.plate().toUpperCase().replace(" ", "").replace("-", "");
        String skeleton = PlateSkeleton.of(canonical);
        String severity = Severity.parse(request.severity(), Severity.high).name();

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO blacklist (plate, plate_canonical, plate_skeleton, reason, severity)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (plate_canonical) WHERE is_active
                DO UPDATE SET reason = EXCLUDED.reason, severity = EXCLUDED.severity
                RETURNING id
                """, Long.class, request.plate(), canonical, skeleton, request.reason(), severity);

        cache.reload();
        return cache.allActive().stream().filter(e -> e.id() == id).findFirst().orElseThrow();
    }

    /** Soft delete: the row stays for the audit trail, it just stops matching. */
    @DeleteMapping("/blacklist/{id}")
    public Map<String, Object> removeFromBlacklist(@PathVariable long id) {
        int updated = jdbcTemplate.update(
                "UPDATE blacklist SET is_active = false WHERE id = ? AND is_active", id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No active blacklist entry with id " + id);
        }
        cache.reload();
        return Map.of("status", "deactivated", "id", id);
    }

    @GetMapping("/restricted-zones")
    public List<RestrictedZone> restrictedZones() {
        return cache.allActiveZones();
    }

    @PostMapping("/restricted-zones")
    public RestrictedZone addRestrictedZone(@RequestBody RestrictedZoneRequest request) {
        if (request.zone() == null || request.zone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone is required");
        }
        String[] types = request.vehicleTypes() == null || request.vehicleTypes().isEmpty()
                ? null : request.vehicleTypes().toArray(new String[0]);

        Long id = jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO restricted_zones
                        (zone, vehicle_types, active_from_hour, active_to_hour, reason)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """);
            ps.setString(1, request.zone());
            if (types == null) {
                ps.setNull(2, java.sql.Types.ARRAY);
            } else {
                ps.setArray(2, con.createArrayOf("text", types));
            }
            setNullableInt(ps, 3, request.activeFromHour());
            setNullableInt(ps, 4, request.activeToHour());
            ps.setString(5, request.reason());
            return ps;
        }, rs -> rs.next() ? rs.getLong("id") : null);

        cache.reload();
        return cache.allActiveZones().stream()
                .filter(z -> z.id() == id).findFirst().orElseThrow();
    }

    @DeleteMapping("/restricted-zones/{id}")
    public Map<String, Object> removeRestrictedZone(@PathVariable long id) {
        int updated = jdbcTemplate.update(
                "UPDATE restricted_zones SET is_active = false WHERE id = ? AND is_active", id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No active restricted zone with id " + id);
        }
        cache.reload();
        return Map.of("status", "deactivated", "id", id);
    }

    private static void setNullableInt(java.sql.PreparedStatement ps, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
