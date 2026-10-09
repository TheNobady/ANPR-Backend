package com.anpr.backend.analytics;

import com.anpr.backend.analytics.dto.CongestionResponse;
import com.anpr.backend.analytics.dto.DensityResponse;
import com.anpr.backend.analytics.dto.HeatmapResponse;
import com.anpr.backend.analytics.dto.OdResponse;
import com.anpr.backend.analytics.dto.SummaryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the aggregate tables. Never observations, never trajectory_events.
 *
 * <p>That restriction is the whole point of the rollups: query cost is bounded by the number
 * of buckets in the window, not by how much traffic has ever passed through the city. It is
 * also why live mode and timeline scrubbing are one code path -- "live" is just the frontend
 * asking for the last fifteen minutes on a timer.
 */
@Service
public class AnalyticsQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsProperties properties;

    public AnalyticsQueryService(JdbcTemplate jdbcTemplate, AnalyticsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * Floors an instant to the start of its bucket.
     *
     * <p>These tables are bucket-granular, so a request for 05:15:24 can only be answered
     * with the bucket that contains it. {@code from} is floored so that bucket is included;
     * the upper bound stays half-open ({@code bucket_start < to}) so a range ending exactly
     * on a boundary does not pull in the bucket that starts there -- that bucket covers time
     * after the caller's range, and if it is the current one it is still filling up, which
     * makes an otherwise-identical query return moving numbers.
     */
    private static Instant snap(Instant instant, int bucketMinutes) {
        long seconds = (long) bucketMinutes * 60;
        return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), seconds) * seconds);
    }

    // ------------------------------------------------------------------- heatmap

    public HeatmapResponse heatmap(Instant requestedFrom, Instant requestedTo) {
        int bucket = properties.getEdgeBucketMinutes();
        Instant from = snap(requestedFrom, bucket);
        Instant to = requestedTo;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT f.edge_id, f.from_camera_id, f.to_camera_id,
                       sum(f.traversal_count) AS weight,
                       -- Samples behind the statistic, not behind the edge. A bucket whose
                       -- sample count was under the minimum contributes no ratio, so counting
                       -- its traversals here would show a well-sampled edge with no band and
                       -- no explanation.
                       COALESCE(sum(f.sample_count)
                                FILTER (WHERE f.congestion_ratio IS NOT NULL), 0) AS sample_count,
                       round(avg(f.median_duration_s))::int AS median_duration_s,
                       max(f.free_flow_s)                   AS free_flow_s,
                       -- Weighted by samples: a quiet bucket should not count as much as a
                       -- busy one when they are averaged together.
                       sum(f.congestion_ratio * f.sample_count)
                           FILTER (WHERE f.congestion_ratio IS NOT NULL)
                         / NULLIF(sum(f.sample_count)
                           FILTER (WHERE f.congestion_ratio IS NOT NULL), 0) AS congestion_ratio,
                       ST_AsGeoJSON(e.path_geom) AS geometry
                  FROM agg_edge_flow f
                  JOIN camera_edges e ON e.id = f.edge_id
                 WHERE f.bucket_minutes = ?
                   AND f.bucket_start >= ? AND f.bucket_start < ?
                 GROUP BY f.edge_id, f.from_camera_id, f.to_camera_id, e.path_geom
                 ORDER BY weight DESC, f.edge_id
                """, properties.getEdgeBucketMinutes(), Timestamp.from(from), Timestamp.from(to));

        long maxWeight = rows.stream()
                .mapToLong(r -> ((Number) r.get("weight")).longValue()).max().orElse(0);

        List<HeatmapResponse.Feature> features = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            long weight = ((Number) r.get("weight")).longValue();
            Double ratio = r.get("congestion_ratio") == null ? null
                    : ((Number) r.get("congestion_ratio")).doubleValue();

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("edge_id", r.get("edge_id"));
            p.put("from_camera_id", r.get("from_camera_id"));
            p.put("to_camera_id", r.get("to_camera_id"));
            p.put("weight", weight);
            p.put("normalized", maxWeight == 0 ? 0.0 : round((double) weight / maxWeight, 4));
            p.put("median_duration_s", r.get("median_duration_s"));
            p.put("free_flow_s", r.get("free_flow_s"));
            p.put("congestion_ratio", ratio == null ? null : round(ratio, 3));
            p.put("congestion_band", properties.bandFor(ratio));
            p.put("sample_count", r.get("sample_count"));
            features.add(new HeatmapResponse.Feature("Feature", (String) r.get("geometry"), p));
        }

        return new HeatmapResponse("FeatureCollection", Instant.now(), from, to,
                properties.getEdgeBucketMinutes(), maxWeight, features.size(), features);
    }

    // ---------------------------------------------------------------- congestion

    public CongestionResponse congestion(Instant requestedFrom, Instant requestedTo, int limit) {
        int bucket = properties.getEdgeBucketMinutes();
        Instant from = snap(requestedFrom, bucket);
        Instant to = requestedTo;
        List<CongestionResponse.Row> rows = jdbcTemplate.query("""
                SELECT f.edge_id, f.from_camera_id, f.to_camera_id,
                       fc.name AS from_camera_name, tc.name AS to_camera_name,
                       round(avg(f.median_duration_s))::int AS median_duration_s,
                       round(avg(f.p85_duration_s))::int    AS p85_duration_s,
                       max(f.free_flow_s)                   AS free_flow_s,
                       sum(f.congestion_ratio * f.sample_count)
                         / NULLIF(sum(f.sample_count), 0)   AS congestion_ratio,
                       sum(f.sample_count)::int             AS sample_count
                  FROM agg_edge_flow f
                  LEFT JOIN cameras fc ON fc.camera_id = f.from_camera_id
                  LEFT JOIN cameras tc ON tc.camera_id = f.to_camera_id
                 WHERE f.bucket_minutes = ?
                   AND f.bucket_start >= ? AND f.bucket_start < ?
                   AND f.congestion_ratio IS NOT NULL
                 GROUP BY f.edge_id, f.from_camera_id, f.to_camera_id, fc.name, tc.name
                 ORDER BY congestion_ratio DESC, f.edge_id
                 LIMIT ?
                """, (rs, n) -> {
            double ratio = rs.getDouble("congestion_ratio");
            return new CongestionResponse.Row(
                    rs.getLong("edge_id"), rs.getString("from_camera_id"),
                    rs.getString("from_camera_name"), rs.getString("to_camera_id"),
                    rs.getString("to_camera_name"),
                    nullableInt(rs, "median_duration_s"), nullableInt(rs, "p85_duration_s"),
                    nullableInt(rs, "free_flow_s"), round(ratio, 3),
                    properties.bandFor(ratio), rs.getInt("sample_count"));
        }, properties.getEdgeBucketMinutes(), Timestamp.from(from), Timestamp.from(to), limit);

        // Edges that were seen, but not often enough to rank. Counted, not hidden.
        Long excluded = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT edge_id) FROM agg_edge_flow
                 WHERE bucket_minutes = ? AND bucket_start >= ? AND bucket_start < ?
                   AND congestion_ratio IS NULL
                """, Long.class, properties.getEdgeBucketMinutes(),
                Timestamp.from(from), Timestamp.from(to));

        return new CongestionResponse(from, to, properties.getMinSamples(),
                excluded == null ? 0 : excluded.intValue(), rows);
    }

    // ------------------------------------------------------------------- density

    /**
     * @param requestedBucketMinutes coarser bucket to re-bin into, or null for the
     *                               stored {@code analytics.density-bucket-minutes}.
     *                               A day of 5-minute buckets across fifty cameras is
     *                               fourteen thousand points and unreadable as a trend;
     *                               re-binning in SQL keeps the response proportional to
     *                               what the caller can actually plot.
     */
    public DensityResponse density(Instant requestedFrom, Instant requestedTo, String cameraId,
                                   Integer requestedBucketMinutes) {
        int stored = properties.getDensityBucketMinutes();
        // Only whole multiples of the stored bucket re-bin cleanly.
        int minutes = requestedBucketMinutes == null
                ? stored
                : Math.max(stored, (requestedBucketMinutes / stored) * stored);

        Instant from = snap(requestedFrom, minutes);
        Instant to = requestedTo;

        // date_bin folds the stored buckets into the requested ones. Summing
        // unique_vehicle_count across buckets over-counts a vehicle present in
        // several of them -- the same caveat the city-wide series already
        // carries, and why the frontend labels these sightings.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT d.camera_id,
                       max(c.name)  AS camera_name,
                       max(c.zone)  AS zone,
                       date_bin(make_interval(mins => ?), d.bucket_start, ?) AS bucket_start,
                       sum(d.observation_count)    AS observation_count,
                       sum(d.unique_vehicle_count) AS unique_vehicle_count,
                       round(avg(d.mean_dwell_ms)) AS mean_dwell_ms
                  FROM agg_camera_density d
                  LEFT JOIN cameras c ON c.camera_id = d.camera_id
                 WHERE d.bucket_minutes = ?
                   AND d.bucket_start >= ? AND d.bucket_start < ?
                   AND (?::text IS NULL OR d.camera_id = ?::text)
                 GROUP BY d.camera_id, 4
                 ORDER BY d.camera_id, 4
                """, minutes, Timestamp.from(from), stored,
                Timestamp.from(from), Timestamp.from(to), cameraId, cameraId);

        Map<String, List<DensityResponse.Point>> byCamera = new LinkedHashMap<>();
        Map<String, String[]> labels = new LinkedHashMap<>();
        Map<Instant, long[]> cityWide = new java.util.TreeMap<>();

        for (Map<String, Object> r : rows) {
            String id = (String) r.get("camera_id");
            Instant bucket = ((Timestamp) r.get("bucket_start")).toInstant();
            long observations = ((Number) r.get("observation_count")).longValue();
            long vehicles = ((Number) r.get("unique_vehicle_count")).longValue();
            Integer dwell = r.get("mean_dwell_ms") == null ? null
                    : ((Number) r.get("mean_dwell_ms")).intValue();

            byCamera.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new DensityResponse.Point(bucket, observations, vehicles, dwell));
            labels.putIfAbsent(id, new String[]{(String) r.get("camera_name"), (String) r.get("zone")});

            long[] totals = cityWide.computeIfAbsent(bucket, k -> new long[3]);
            totals[0] += observations;
            totals[1] += vehicles;
            if (dwell != null) {
                totals[2] = Math.max(totals[2], 0);
            }
        }

        List<DensityResponse.Series> series = new ArrayList<>();
        byCamera.forEach((id, points) -> series.add(new DensityResponse.Series(
                id, labels.get(id)[0], labels.get(id)[1], points)));

        // The city-wide roll-up only makes sense when more than one camera is in view.
        if (cameraId == null) {
            List<DensityResponse.Point> total = cityWide.entrySet().stream()
                    .map(e -> new DensityResponse.Point(
                            e.getKey(), e.getValue()[0], e.getValue()[1], null))
                    .toList();
            series.add(new DensityResponse.Series(null, "All cameras", null, total));
        }
        return new DensityResponse(from, to, minutes, series);
    }

    // ----------------------------------------------------------------- OD matrix

    public OdResponse od(Instant requestedFrom, Instant requestedTo) {
        int minutes = properties.getOdBucketMinutes();
        Instant from = snap(requestedFrom, minutes);
        Instant to = requestedTo;
        List<OdResponse.Cell> matrix = jdbcTemplate.query("""
                SELECT origin_zone, destination_zone, sum(vehicle_count)::bigint AS n
                  FROM agg_od_matrix
                 WHERE bucket_minutes = ? AND bucket_start >= ? AND bucket_start < ?
                 GROUP BY origin_zone, destination_zone
                 ORDER BY n DESC, origin_zone, destination_zone
                """, (rs, n) -> new OdResponse.Cell(rs.getString("origin_zone"),
                        rs.getString("destination_zone"), rs.getLong("n")),
                minutes, Timestamp.from(from), Timestamp.from(to));

        Map<String, Object> coverage = jdbcTemplate.queryForMap("""
                SELECT COALESCE(sum(vehicles_observed), 0)       AS observed,
                       COALESCE(sum(vehicles_with_both_ends), 0) AS both_ends
                  FROM agg_od_coverage
                 WHERE bucket_minutes = ? AND bucket_start >= ? AND bucket_start < ?
                """, minutes, Timestamp.from(from), Timestamp.from(to));

        long observed = ((Number) coverage.get("observed")).longValue();
        long bothEnds = ((Number) coverage.get("both_ends")).longValue();

        List<String> zones = jdbcTemplate.queryForList(
                "SELECT DISTINCT zone FROM cameras WHERE is_active AND zone IS NOT NULL ORDER BY zone",
                String.class);

        return new OdResponse(from, to, zones, matrix,
                new OdResponse.Coverage(bothEnds, observed,
                        observed == 0 ? null : round((double) bothEnds / observed, 3),
                        OdResponse.Coverage.NOTE));
    }

    // -------------------------------------------------------------------- summary

    public SummaryResponse summary(Instant requestedFrom, Instant requestedTo) {
        Instant from = snap(requestedFrom, properties.getDensityBucketMinutes());
        Instant to = requestedTo;
        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);
        int densityMinutes = properties.getDensityBucketMinutes();
        int edgeMinutes = properties.getEdgeBucketMinutes();

        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COALESCE(sum(observation_count), 0)      AS observations,
                       COALESCE(sum(unique_vehicle_count), 0)   AS vehicles,
                       count(DISTINCT camera_id)                AS cameras
                  FROM agg_camera_density
                 WHERE bucket_minutes = ? AND bucket_start >= ? AND bucket_start < ?
                """, densityMinutes, fromTs, toTs);

        Map<String, Long> alerts = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                SELECT severity, count(*) AS n FROM alerts
                 WHERE occurred_at >= ? AND occurred_at < ? GROUP BY severity
                """, fromTs, toTs)
                .forEach(r -> alerts.put((String) r.get("severity"), ((Number) r.get("n")).longValue()));

        List<SummaryResponse.BusiestCamera> busiest = jdbcTemplate.query("""
                SELECT d.camera_id, c.name, sum(d.observation_count)::bigint AS n
                  FROM agg_camera_density d LEFT JOIN cameras c ON c.camera_id = d.camera_id
                 WHERE d.bucket_minutes = ? AND d.bucket_start >= ? AND d.bucket_start < ?
                 GROUP BY d.camera_id, c.name ORDER BY n DESC, d.camera_id LIMIT 1
                """, (rs, n) -> new SummaryResponse.BusiestCamera(
                        rs.getString("camera_id"), rs.getString("name"), rs.getLong("n")),
                densityMinutes, fromTs, toTs);

        List<SummaryResponse.WorstEdge> worst = jdbcTemplate.query("""
                SELECT edge_id, from_camera_id, to_camera_id,
                       sum(congestion_ratio * sample_count) / NULLIF(sum(sample_count), 0) AS ratio,
                       sum(sample_count)::int AS samples
                  FROM agg_edge_flow
                 WHERE bucket_minutes = ? AND bucket_start >= ? AND bucket_start < ?
                   AND congestion_ratio IS NOT NULL
                 GROUP BY edge_id, from_camera_id, to_camera_id
                 ORDER BY ratio DESC, edge_id LIMIT 1
                """, (rs, n) -> {
            double ratio = rs.getDouble("ratio");
            return new SummaryResponse.WorstEdge(rs.getLong("edge_id"),
                    rs.getString("from_camera_id"), rs.getString("to_camera_id"),
                    round(ratio, 3), properties.bandFor(ratio), rs.getInt("samples"));
        }, edgeMinutes, fromTs, toTs);

        Double meanRatio = jdbcTemplate.queryForObject("""
                SELECT avg(congestion_ratio) FROM agg_edge_flow
                 WHERE bucket_minutes = ? AND bucket_start >= ? AND bucket_start < ?
                   AND congestion_ratio IS NOT NULL
                """, Double.class, edgeMinutes, fromTs, toTs);

        return new SummaryResponse(from, to,
                ((Number) totals.get("observations")).longValue(),
                ((Number) totals.get("vehicles")).longValue(),
                ((Number) totals.get("cameras")).longValue(),
                alerts,
                busiest.isEmpty() ? null : busiest.get(0),
                worst.isEmpty() ? null : worst.get(0),
                meanRatio == null ? null : round(meanRatio, 3));
    }

    // -------------------------------------------------------------------- support

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
