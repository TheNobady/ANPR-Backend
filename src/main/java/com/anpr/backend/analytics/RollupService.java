package com.anpr.backend.analytics;

import com.anpr.backend.common.FailureCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Computes the aggregate tables the dashboard reads.
 *
 * <p>This is the only code that reads raw observations and trajectory events for analytics;
 * every query endpoint reads the aggregates it writes.
 *
 * <p><b>Idempotent per bucket.</b> Each pass deletes the buckets in its range and rewrites
 * them, so re-running repairs rather than duplicates -- and it gets re-run constantly,
 * because every simulator backfill and every identity rebuild changes the trajectories
 * underneath.
 */
@Service
public class RollupService {

    private static final Logger log = LoggerFactory.getLogger(RollupService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsProperties properties;
    private final FailureCounters failures;

    public RollupService(JdbcTemplate jdbcTemplate, AnalyticsProperties properties,
                         FailureCounters failures) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.failures = failures;
    }

    public record RollupSummary(Instant from, Instant to, int densityRows, int edgeRows,
                                int odRows, long elapsedMs) {
    }

    /**
     * Keeps the recent past fresh. Recomputes the last few buckets rather than only the
     * newest, so an event that lands a little late still lands in a bucket that gets redone.
     */
    @Scheduled(fixedDelayString = "${analytics.rollup-interval-ms:60000}")
    public void scheduledRollup() {
        try {
            Instant now = Instant.now();
            int buckets = properties.getRollupBuckets();
            recompute(now.minus(Duration.ofMinutes(
                    (long) properties.getOdBucketMinutes() * buckets)), now);
        } catch (Exception e) {
            failures.increment(FailureCounters.ROLLUP);
            log.error("analytics: scheduled rollup failed", e);
        }
    }

    /** Recomputes every bucket touching {@code [from, to]} from scratch. */
    @Transactional
    public RollupSummary recompute(Instant from, Instant to) {
        long began = System.nanoTime();
        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);

        int density = rollupDensity(fromTs, toTs);
        int edges = rollupEdgeFlow(fromTs, toTs);
        int od = rollupOdMatrix(fromTs, toTs);

        long elapsedMs = (System.nanoTime() - began) / 1_000_000;
        log.info("analytics: recomputed {} -> {} ({} density, {} edge, {} od rows) in {} ms",
                from, to, density, edges, od, elapsedMs);
        return new RollupSummary(from, to, density, edges, od, elapsedMs);
    }

    // ------------------------------------------------------------------- density

    private int rollupDensity(Timestamp from, Timestamp to) {
        int minutes = properties.getDensityBucketMinutes();
        jdbcTemplate.update("""
                DELETE FROM agg_camera_density
                 WHERE bucket_minutes = ?
                   AND bucket_start >= %s AND bucket_start <= ?::timestamptz
                """.formatted(bucketExpr("?::timestamptz", minutes)), minutes, from, to);

        return jdbcTemplate.update("""
                WITH base AS (
                    SELECT o.camera_id,
                           %s AS bucket_start,
                           o.dwell_ms, o.vehicle_type, te.vehicle_id
                      FROM observations o
                      LEFT JOIN trajectory_events te ON te.observation_id = o.id
                     WHERE o.first_seen_at >= %s
                       AND o.first_seen_at <= ?::timestamptz
                ),
                totals AS (
                    SELECT camera_id, bucket_start,
                           count(*) AS observation_count,
                           count(DISTINCT vehicle_id) AS unique_vehicle_count,
                           round(avg(dwell_ms))::int AS mean_dwell_ms
                      FROM base GROUP BY camera_id, bucket_start
                ),
                types AS (
                    SELECT camera_id, bucket_start, jsonb_object_agg(vehicle_type, n) AS type_counts
                      FROM (SELECT camera_id, bucket_start, vehicle_type, count(*) AS n
                              FROM base WHERE vehicle_type IS NOT NULL
                             GROUP BY camera_id, bucket_start, vehicle_type) per_type
                     GROUP BY camera_id, bucket_start
                )
                INSERT INTO agg_camera_density
                    (camera_id, bucket_start, bucket_minutes, observation_count,
                     unique_vehicle_count, mean_dwell_ms, type_counts)
                SELECT t.camera_id, t.bucket_start, ?, t.observation_count,
                       t.unique_vehicle_count, t.mean_dwell_ms,
                       COALESCE(ty.type_counts, '{}'::jsonb)
                  FROM totals t
                  LEFT JOIN types ty
                         ON ty.camera_id = t.camera_id AND ty.bucket_start = t.bucket_start
                ON CONFLICT (camera_id, bucket_start, bucket_minutes) DO UPDATE SET
                    observation_count    = EXCLUDED.observation_count,
                    unique_vehicle_count = EXCLUDED.unique_vehicle_count,
                    mean_dwell_ms        = EXCLUDED.mean_dwell_ms,
                    type_counts          = EXCLUDED.type_counts,
                    computed_at          = now()
                """.formatted(bucketExpr("o.first_seen_at", minutes),
                        bucketExpr("?::timestamptz", minutes)),
                from, to, minutes);
    }

    // ----------------------------------------------------------------- edge flow

    private int rollupEdgeFlow(Timestamp from, Timestamp to) {
        int minutes = properties.getEdgeBucketMinutes();
        jdbcTemplate.update("""
                DELETE FROM agg_edge_flow
                 WHERE bucket_minutes = ?
                   AND bucket_start >= %s AND bucket_start <= ?::timestamptz
                """.formatted(bucketExpr("?::timestamptz", minutes)), minutes, from, to);

        // The lag runs over every trajectory event of a vehicle, then filters to the ones
        // that crossed an edge -- filtering first would compare a sighting to the wrong
        // predecessor and invent travel times that never happened.
        return jdbcTemplate.update("""
                WITH seq AS (
                    SELECT te.vehicle_id, te.sequence_number, te.edge_id, o.first_seen_at,
                           lag(o.first_seen_at) OVER (PARTITION BY te.vehicle_id
                                                      ORDER BY te.sequence_number) AS prev_at
                      FROM trajectory_events te
                      JOIN observations o ON o.id = te.observation_id
                ),
                hops AS (
                    SELECT s.edge_id,
                           %s AS bucket_start,
                           EXTRACT(epoch FROM s.first_seen_at - s.prev_at) AS duration_s
                      FROM seq s
                     WHERE s.edge_id IS NOT NULL
                       AND s.prev_at IS NOT NULL
                       AND s.first_seen_at >= %s
                       AND s.first_seen_at <= ?::timestamptz
                ),
                stats AS (
                    SELECT h.edge_id, h.bucket_start,
                           count(*) AS sample_count,
                           percentile_cont(0.5)  WITHIN GROUP (ORDER BY h.duration_s) AS median_s,
                           percentile_cont(0.85) WITHIN GROUP (ORDER BY h.duration_s) AS p85_s
                      FROM hops h GROUP BY h.edge_id, h.bucket_start
                )
                INSERT INTO agg_edge_flow
                    (edge_id, from_camera_id, to_camera_id, bucket_start, bucket_minutes,
                     traversal_count, median_duration_s, p85_duration_s, free_flow_s,
                     congestion_ratio, congestion_band, sample_count)
                SELECT s.edge_id, e.from_camera_id, e.to_camera_id, s.bucket_start, ?,
                       s.sample_count,
                       CASE WHEN s.sample_count >= ? THEN round(s.median_s)::int END,
                       CASE WHEN s.sample_count >= ? THEN round(s.p85_s)::int END,
                       e.t_typical_s,
                       CASE WHEN s.sample_count >= ? AND e.t_typical_s > 0
                            THEN (s.median_s / e.t_typical_s)::real END,
                       CASE WHEN s.sample_count >= ? AND e.t_typical_s > 0 THEN
                            CASE WHEN s.median_s / e.t_typical_s <  ? THEN 'free'
                                 WHEN s.median_s / e.t_typical_s <  ? THEN 'moderate'
                                 WHEN s.median_s / e.t_typical_s <= ? THEN 'heavy'
                                 ELSE 'severe' END END,
                       s.sample_count
                  FROM stats s JOIN camera_edges e ON e.id = s.edge_id
                ON CONFLICT (edge_id, bucket_start, bucket_minutes) DO UPDATE SET
                    traversal_count   = EXCLUDED.traversal_count,
                    median_duration_s = EXCLUDED.median_duration_s,
                    p85_duration_s    = EXCLUDED.p85_duration_s,
                    congestion_ratio  = EXCLUDED.congestion_ratio,
                    congestion_band   = EXCLUDED.congestion_band,
                    sample_count      = EXCLUDED.sample_count,
                    computed_at       = now()
                """.formatted(bucketExpr("s.first_seen_at", minutes),
                        bucketExpr("?::timestamptz", minutes)),
                // Placeholder order: window start, window end, bucket size, then the
                // min-sample gate four times and the three band edges.
                from, to, minutes,
                properties.getMinSamples(), properties.getMinSamples(),
                properties.getMinSamples(), properties.getMinSamples(),
                properties.getBandModerate(), properties.getBandHeavy(), properties.getBandSevere());
    }

    // ---------------------------------------------------------------- OD matrix

    private int rollupOdMatrix(Timestamp from, Timestamp to) {
        int minutes = properties.getOdBucketMinutes();
        String bucketOfFrom = bucketExpr("?::timestamptz", minutes);

        jdbcTemplate.update(("DELETE FROM agg_od_matrix WHERE bucket_minutes = ? "
                + "AND bucket_start >= " + bucketOfFrom + " AND bucket_start <= ?::timestamptz"),
                minutes, from, to);
        jdbcTemplate.update(("DELETE FROM agg_od_coverage WHERE bucket_minutes = ? "
                + "AND bucket_start >= " + bucketOfFrom + " AND bucket_start <= ?::timestamptz"),
                minutes, from, to);

        // A journey is attributed to the bucket its first sighting falls in.
        String journeys = """
                WITH sightings AS (
                    SELECT te.vehicle_id, o.first_seen_at, c.zone
                      FROM trajectory_events te
                      JOIN observations o ON o.id = te.observation_id
                      JOIN cameras c      ON c.camera_id = o.camera_id
                     WHERE o.first_seen_at >= %s
                       AND o.first_seen_at <= ?::timestamptz
                       AND c.zone IS NOT NULL
                ),
                journeys AS (
                    SELECT vehicle_id,
                           %s AS bucket_start,
                           (array_agg(zone ORDER BY first_seen_at))[1]      AS origin_zone,
                           (array_agg(zone ORDER BY first_seen_at DESC))[1] AS destination_zone,
                           count(*) AS sightings
                      FROM sightings
                     GROUP BY vehicle_id
                )
                """.formatted(bucketExpr("?::timestamptz", minutes),
                        bucketExpr("min(first_seen_at)", minutes));

        jdbcTemplate.update(journeys + """
                INSERT INTO agg_od_coverage
                    (bucket_start, bucket_minutes, vehicles_observed, vehicles_with_both_ends)
                SELECT bucket_start, ?, count(*), count(*) FILTER (WHERE sightings >= 2)
                  FROM journeys GROUP BY bucket_start
                ON CONFLICT (bucket_start, bucket_minutes) DO UPDATE SET
                    vehicles_observed       = EXCLUDED.vehicles_observed,
                    vehicles_with_both_ends = EXCLUDED.vehicles_with_both_ends,
                    computed_at             = now()
                """, from, to, minutes);

        return jdbcTemplate.update(journeys + """
                INSERT INTO agg_od_matrix
                    (origin_zone, destination_zone, bucket_start, bucket_minutes, vehicle_count)
                SELECT origin_zone, destination_zone, bucket_start, ?, count(*)
                  FROM journeys
                 WHERE sightings >= 2
                 GROUP BY origin_zone, destination_zone, bucket_start
                ON CONFLICT (origin_zone, destination_zone, bucket_start, bucket_minutes)
                DO UPDATE SET vehicle_count = EXCLUDED.vehicle_count, computed_at = now()
                """, from, to, minutes);
    }

    /** Floors a timestamp to the start of its bucket. */
    private static String bucketExpr(String column, int minutes) {
        return "to_timestamp(floor(extract(epoch FROM %s) / %d) * %d)"
                .formatted(column, minutes * 60, minutes * 60);
    }
}
