package com.anpr.backend.sim;

import com.anpr.backend.sim.dto.BackfillRequest;
import com.anpr.backend.sim.dto.BackfillResponse;
import com.anpr.backend.sim.dto.ScenarioRequest;
import com.anpr.backend.sim.dto.ScenarioResponse;
import com.anpr.backend.sim.dto.SimStartRequest;
import com.anpr.backend.sim.dto.SimStatusResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulator control surface.
 *
 * <p>Gated on the {@code sim} profile: a production build has no way to inject synthetic
 * traffic, because these beans do not exist in it.
 */
@RestController
@RequestMapping("/api/sim")
@Profile("sim")
public class SimController {

    private final SimulationEngine engine;

    public SimController(SimulationEngine engine) {
        this.engine = engine;
    }

    /** Returns as soon as the schedule is set; traffic runs on the scheduler. */
    @PostMapping("/start")
    public SimStatusResponse start(@RequestBody(required = false) SimStartRequest request) {
        return engine.start(request == null
                ? new SimStartRequest(null, null, null, null, null, null)
                : request);
    }

    @PostMapping("/stop")
    public SimStatusResponse stop() {
        return engine.stop();
    }

    @GetMapping("/status")
    public SimStatusResponse status() {
        return engine.status();
    }

    /** Blocks until the whole block of history has been posted, then reports on it. */
    @PostMapping("/backfill")
    public BackfillResponse backfill(@RequestBody(required = false) BackfillRequest request) {
        return engine.backfill(request == null
                ? new BackfillRequest(null, null, null, null, null, null)
                : request);
    }

    @PostMapping("/scenario")
    public ScenarioResponse scenario(@RequestBody ScenarioRequest request) {
        return engine.runScenario(request);
    }
}
