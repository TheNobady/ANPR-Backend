package com.anpr.backend.identity;

import com.anpr.backend.identity.dto.EvaluationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Scores the resolver against the simulator's ground truth.
 *
 * <p>Only observations with a {@code sim_ground_truth} row count. Real perception events
 * have no truth to compare against, so they are simply outside the measurement.
 *
 * <p>A "resolver link" is a consecutive pair inside one resolved vehicle: sequence n and
 * n+1. A "true link" is a consecutive pair of observations of the same simulated vehicle,
 * ordered by time. Precision asks how many of the former are real; recall asks how many
 * of the latter were found.
 */
@Service
public class IdentityEvaluator {

    /** Consecutive pairs the resolver asserted, with whether each is actually correct. */
    private static final String PREDICTED_PAIRS = """
            WITH linked AS (
                SELECT te.vehicle_id, te.sequence_number, te.observation_id,
                       g.sim_vehicle_id, o.plate_text IS NULL AS plate_null
                  FROM trajectory_events te
                  JOIN observations o      ON o.id = te.observation_id
                  JOIN sim_ground_truth g  ON g.event_id = o.event_id
            ),
            pairs AS (
                SELECT a.sim_vehicle_id AS from_truth, b.sim_vehicle_id AS to_truth, b.plate_null
                  FROM linked a
                  JOIN linked b ON b.vehicle_id = a.vehicle_id
                               AND b.sequence_number = a.sequence_number + 1
            )
            SELECT %s
              FROM pairs
            """;

    /** Consecutive pairs that really happened, with whether the resolver found each. */
    private static final String TRUE_PAIRS = """
            WITH truth AS (
                SELECT g.sim_vehicle_id, o.id AS observation_id, o.first_seen_at,
                       o.plate_text IS NULL AS plate_null,
                       lead(o.id) OVER w AS next_observation_id,
                       lead(o.plate_text IS NULL) OVER w AS next_plate_null
                  FROM observations o
                  JOIN sim_ground_truth g ON g.event_id = o.event_id
                WINDOW w AS (PARTITION BY g.sim_vehicle_id ORDER BY o.first_seen_at, o.id)
            ),
            pairs AS (
                SELECT t.observation_id, t.next_observation_id, t.next_plate_null,
                       EXISTS (
                           SELECT 1
                             FROM trajectory_events a
                             JOIN trajectory_events b ON b.vehicle_id = a.vehicle_id
                                                     AND b.sequence_number = a.sequence_number + 1
                            WHERE a.observation_id = t.observation_id
                              AND b.observation_id = t.next_observation_id
                       ) AS found
                  FROM truth t
                 WHERE t.next_observation_id IS NOT NULL
            )
            SELECT %s
              FROM pairs
            """;

    private final JdbcTemplate jdbcTemplate;

    public IdentityEvaluator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public EvaluationResponse evaluate() {
        long withTruth = count("""
                SELECT count(*) FROM observations o
                  JOIN sim_ground_truth g ON g.event_id = o.event_id
                """);
        long trueVehicles = count("SELECT count(DISTINCT sim_vehicle_id) FROM sim_ground_truth g "
                + "WHERE EXISTS (SELECT 1 FROM observations o WHERE o.event_id = g.event_id)");
        long resolvedVehicles = count("SELECT count(*) FROM vehicles");

        Map<String, Object> precision = jdbcTemplate.queryForMap(PREDICTED_PAIRS.formatted("""
                count(*) AS total,
                count(*) FILTER (WHERE from_truth = to_truth) AS correct,
                count(*) FILTER (WHERE plate_null) AS total_null_plate,
                count(*) FILTER (WHERE plate_null AND from_truth = to_truth) AS correct_null_plate,
                count(*) FILTER (WHERE NOT plate_null) AS total_with_plate,
                count(*) FILTER (WHERE NOT plate_null AND from_truth = to_truth) AS correct_with_plate
                """));

        Map<String, Object> recall = jdbcTemplate.queryForMap(TRUE_PAIRS.formatted("""
                count(*) AS total,
                count(*) FILTER (WHERE found) AS found,
                count(*) FILTER (WHERE next_plate_null) AS total_null_plate,
                count(*) FILTER (WHERE next_plate_null AND found) AS found_null_plate,
                count(*) FILTER (WHERE NOT next_plate_null) AS total_with_plate,
                count(*) FILTER (WHERE NOT next_plate_null AND found) AS found_with_plate
                """));

        Map<String, Object> fragmentation = jdbcTemplate.queryForMap("""
                WITH per_true AS (
                    SELECT g.sim_vehicle_id, count(DISTINCT te.vehicle_id) AS resolved_ids
                      FROM sim_ground_truth g
                      JOIN observations o       ON o.event_id = g.event_id
                      JOIN trajectory_events te ON te.observation_id = o.id
                     GROUP BY g.sim_vehicle_id
                )
                SELECT count(*) AS true_vehicles,
                       COALESCE(sum(resolved_ids), 0) AS distinct_resolved_ids,
                       avg(resolved_ids) AS mean
                  FROM per_true
                """);

        Map<String, Object> purity = jdbcTemplate.queryForMap("""
                WITH per_resolved AS (
                    SELECT te.vehicle_id, count(DISTINCT g.sim_vehicle_id) AS true_ids
                      FROM trajectory_events te
                      JOIN observations o      ON o.id = te.observation_id
                      JOIN sim_ground_truth g  ON g.event_id = o.event_id
                     GROUP BY te.vehicle_id
                )
                SELECT count(*) AS total, count(*) FILTER (WHERE true_ids = 1) AS pure
                  FROM per_resolved
                """);

        return new EvaluationResponse(
                withTruth,
                trueVehicles,
                resolvedVehicles,
                EvaluationResponse.Rate.of(asLong(precision.get("correct")), asLong(precision.get("total"))),
                EvaluationResponse.Rate.of(asLong(recall.get("found")), asLong(recall.get("total"))),
                new EvaluationResponse.Fragmentation(
                        fragmentation.get("mean") == null ? null
                                : ((Number) fragmentation.get("mean")).doubleValue(),
                        asLong(fragmentation.get("true_vehicles")),
                        asLong(fragmentation.get("distinct_resolved_ids"))),
                EvaluationResponse.Rate.of(asLong(purity.get("pure")), asLong(purity.get("total"))),
                new EvaluationResponse.Split(
                        EvaluationResponse.Rate.of(asLong(precision.get("correct_with_plate")),
                                asLong(precision.get("total_with_plate"))),
                        EvaluationResponse.Rate.of(asLong(recall.get("found_with_plate")),
                                asLong(recall.get("total_with_plate")))),
                new EvaluationResponse.Split(
                        EvaluationResponse.Rate.of(asLong(precision.get("correct_null_plate")),
                                asLong(precision.get("total_null_plate"))),
                        EvaluationResponse.Rate.of(asLong(recall.get("found_null_plate")),
                                asLong(recall.get("total_null_plate")))));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
