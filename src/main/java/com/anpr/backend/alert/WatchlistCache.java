package com.anpr.backend.alert;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blacklist and restricted zones, held in memory.
 *
 * <p>Every observation is checked against both, on the single-threaded identity consumer.
 * A query per observation would put a database round trip in the middle of the hot loop for
 * data that changes a few times a day; this is a map lookup instead.
 *
 * <p>Writes invalidate rather than expire, so an operator adding a plate sees it take effect
 * on the very next observation without a restart.
 */
@Component
public class WatchlistCache {

    private static final Logger log = LoggerFactory.getLogger(WatchlistCache.class);

    private final JdbcTemplate jdbcTemplate;

    private volatile Map<String, BlacklistEntry> byCanonical = Map.of();
    private volatile Map<String, BlacklistEntry> bySkeleton = Map.of();
    private volatile Map<String, List<RestrictedZone>> zonesByZone = Map.of();

    public WatchlistCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void reload() {
        List<BlacklistEntry> entries = jdbcTemplate.query(
                "SELECT * FROM blacklist WHERE is_active ORDER BY id", blacklistMapper());
        Map<String, BlacklistEntry> canonical = new HashMap<>();
        Map<String, BlacklistEntry> skeleton = new HashMap<>();
        for (BlacklistEntry e : entries) {
            canonical.put(e.plateCanonical(), e);
            // First writer wins: two blacklisted plates can share a skeleton, and the exact
            // lookup is tried first anyway.
            skeleton.putIfAbsent(e.plateSkeleton(), e);
        }

        List<RestrictedZone> zones = jdbcTemplate.query(
                "SELECT * FROM restricted_zones WHERE is_active ORDER BY id", zoneMapper());
        Map<String, List<RestrictedZone>> byZone = new HashMap<>();
        for (RestrictedZone z : zones) {
            byZone.computeIfAbsent(z.zone(), k -> new ArrayList<>()).add(z);
        }

        this.byCanonical = Map.copyOf(canonical);
        this.bySkeleton = Map.copyOf(skeleton);
        this.zonesByZone = Map.copyOf(byZone);
        log.info("watchlist: {} blacklist entries, {} restricted zones loaded",
                entries.size(), zones.size());
    }

    /** Exact plate first, then skeleton -- a confusable glyph should not hide a wanted vehicle. */
    public Match lookup(String plateCanonical, String plateSkeleton) {
        if (plateCanonical != null) {
            BlacklistEntry exact = byCanonical.get(plateCanonical);
            if (exact != null) {
                return new Match(exact, "exact");
            }
        }
        if (plateSkeleton != null) {
            BlacklistEntry fuzzy = bySkeleton.get(plateSkeleton);
            if (fuzzy != null) {
                return new Match(fuzzy, "skeleton");
            }
        }
        return null;
    }

    /** @param matchType {@code exact} or {@code skeleton} -- an operator acting on a fuzzy hit deserves to know */
    public record Match(BlacklistEntry entry, String matchType) {
    }

    public List<RestrictedZone> zonesFor(String zone) {
        return zone == null ? List.of() : zonesByZone.getOrDefault(zone, List.of());
    }

    public List<BlacklistEntry> allActive() {
        return jdbcTemplate.query("SELECT * FROM blacklist WHERE is_active ORDER BY id DESC",
                blacklistMapper());
    }

    public List<RestrictedZone> allActiveZones() {
        return jdbcTemplate.query("SELECT * FROM restricted_zones WHERE is_active ORDER BY id DESC",
                zoneMapper());
    }

    private org.springframework.jdbc.core.RowMapper<BlacklistEntry> blacklistMapper() {
        return (rs, n) -> {
            Timestamp created = rs.getTimestamp("created_at");
            return new BlacklistEntry(
                    rs.getLong("id"), rs.getString("plate"), rs.getString("plate_canonical"),
                    rs.getString("plate_skeleton"), rs.getString("reason"), rs.getString("severity"),
                    rs.getBoolean("is_active"), created == null ? null : created.toInstant());
        };
    }

    private org.springframework.jdbc.core.RowMapper<RestrictedZone> zoneMapper() {
        return (rs, n) -> {
            Array types = rs.getArray("vehicle_types");
            List<String> vehicleTypes = types == null ? null
                    : Arrays.asList((String[]) types.getArray());
            int fromHour = rs.getInt("active_from_hour");
            Integer from = rs.wasNull() ? null : fromHour;
            int toHour = rs.getInt("active_to_hour");
            Integer to = rs.wasNull() ? null : toHour;
            return new RestrictedZone(rs.getLong("id"), rs.getString("zone"), vehicleTypes,
                    from, to, rs.getString("reason"), rs.getBoolean("is_active"));
        };
    }
}
