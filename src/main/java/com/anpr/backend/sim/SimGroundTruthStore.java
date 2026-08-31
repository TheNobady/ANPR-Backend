package com.anpr.backend.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records what the simulator actually did, so identity resolution can be scored.
 *
 * <p>Written straight to the database and never carried in the event payload: the payload
 * has to stay byte-identical to the perception contract, and a resolver that could read
 * the answer off the wire would not be measuring anything.
 */
@Component
@Profile("sim")
public class SimGroundTruthStore {

    private static final Logger log = LoggerFactory.getLogger(SimGroundTruthStore.class);

    private static final String INSERT = """
            INSERT INTO sim_ground_truth (event_id, sim_vehicle_id, true_plate)
            VALUES (?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public SimGroundTruthStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String eventId, String simVehicleId, String truePlate) {
        try {
            jdbcTemplate.update(INSERT, eventId, simVehicleId, truePlate);
        } catch (RuntimeException e) {
            // Ground truth is measurement scaffolding; losing a row must not stop traffic.
            log.warn("simulator: could not record ground truth for {}", eventId, e);
        }
    }
}
