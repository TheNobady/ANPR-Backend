package com.anpr.backend.identity;

import com.anpr.backend.identity.dto.EvaluationResponse;
import com.anpr.backend.identity.dto.IdentityStatsResponse;
import com.anpr.backend.identity.dto.RebuildResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    private final IdentityRebuildService rebuildService;
    private final IdentityEvaluator evaluator;
    private final IdentityQueue queue;
    private final JdbcTemplate jdbcTemplate;

    public IdentityController(IdentityRebuildService rebuildService, IdentityEvaluator evaluator,
                              IdentityQueue queue, JdbcTemplate jdbcTemplate) {
        this.rebuildService = rebuildService;
        this.evaluator = evaluator;
        this.queue = queue;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Re-resolves every observation from scratch, in time order. Blocks until done. */
    @PostMapping("/rebuild")
    public RebuildResponse rebuild() {
        return rebuildService.rebuild();
    }

    @GetMapping("/evaluate")
    public EvaluationResponse evaluate() {
        return evaluator.evaluate();
    }

    @GetMapping("/stats")
    public IdentityStatsResponse stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, count(*) AS n FROM vehicles GROUP BY status ORDER BY status");
        rows.forEach(r -> byStatus.put((String) r.get("status"), ((Number) r.get("n")).longValue()));

        Long vehicles = jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Long.class);
        Double meanSightings = jdbcTemplate.queryForObject(
                "SELECT avg(sighting_count) FROM vehicles", Double.class);
        Long links = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trajectory_events WHERE sequence_number > 1", Long.class);
        Long unlinked = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM observations o
                 WHERE NOT EXISTS (SELECT 1 FROM trajectory_events te WHERE te.observation_id = o.id)
                """, Long.class);

        return new IdentityStatsResponse(
                byStatus,
                vehicles == null ? 0 : vehicles,
                meanSightings == null ? 0.0 : meanSightings,
                links == null ? 0 : links,
                unlinked == null ? 0 : unlinked,
                queue.depth(), queue.processed(), queue.dropped());
    }
}
