package com.anpr.backend.identity;

import com.anpr.backend.identity.dto.RebuildResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.anpr.backend.analytics.RollupService;
import com.anpr.backend.stream.EventStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Rebuilds every vehicle and trajectory from scratch, in {@code first_seen_at} order.
 *
 * <p>Necessary rather than merely convenient: the simulator's backfill posts an hour of
 * history from eight threads at once, so observations arrive in essentially arbitrary
 * order, and greedy linking is only meaningful in time order. It is also the development
 * loop -- change a weight in {@code application.yml}, rebuild, re-measure.
 *
 * <p>Runs the same {@link IdentityService} the live queue does. There is no second copy
 * of the logic.
 */
@Service
public class IdentityRebuildService {

    private static final Logger log = LoggerFactory.getLogger(IdentityRebuildService.class);

    private final JdbcTemplate jdbcTemplate;
    private final IdentityPipeline pipeline;
    private final IdentityQueue queue;

    private final EventStream stream;
    private final RollupService rollups;

    public IdentityRebuildService(JdbcTemplate jdbcTemplate, IdentityPipeline pipeline,
                                  IdentityQueue queue, EventStream stream,
                                  RollupService rollups) {
        this.rollups = rollups;
        this.jdbcTemplate = jdbcTemplate;
        this.pipeline = pipeline;
        this.queue = queue;
        this.stream = stream;
    }

    public RebuildResponse rebuild() {
        // The live consumer is held off for the whole rebuild: it writes the same two
        // tables, and the clear below is not safe against a concurrent insert.
        // A rebuild replays hours of history and regenerates every alert with it. None of
        // that is live, so the stream is silenced for the duration.
        stream.mute();
        try {
            return queue.runExclusively(this::doRebuild);
        } finally {
            stream.unmute();
        }
    }

    private RebuildResponse doRebuild() {
        long began = System.nanoTime();
        clearResolvedIdentities();
        pipeline.clearAlerts();

        // One ordered pass. The id list is fetched up front rather than paged:
        // keyset pagination on a timestamp risks re-processing rows that share a
        // microsecond, and re-processing would mint duplicate vehicles.
        List<Long> observationIds = jdbcTemplate.queryForList(
                "SELECT id FROM observations ORDER BY first_seen_at, id", Long.class);

        long processed = 0;
        for (Long observationId : observationIds) {
            pipeline.process(observationId);
            if (++processed % 2000 == 0) {
                log.info("identity rebuild: {}/{} observations processed",
                        processed, observationIds.size());
            }
        }

        Long vehicles = jdbcTemplate.queryForObject("SELECT count(*) FROM vehicles", Long.class);
        Long links = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trajectory_events WHERE sequence_number > 1", Long.class);
        Long unlinked = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM observations o
                 WHERE NOT EXISTS (SELECT 1 FROM trajectory_events te WHERE te.observation_id = o.id)
                """, Long.class);

        long elapsedMs = (System.nanoTime() - began) / 1_000_000;
        log.info("identity rebuild: {} observations -> {} vehicles, {} links, in {} ms",
                processed, vehicles, links, elapsedMs);
        // Every aggregate downstream of these trajectories is now stale. Recompute the range
        // the rebuild actually touched rather than leaving someone to remember to do it.
        RollupService.RollupSummary rollup = recomputeAnalytics();

        return new RebuildResponse("complete", processed, orZero(vehicles), orZero(links),
                orZero(unlinked), elapsedMs,
                new RebuildResponse.AnalyticsRecomputed(rollup.from(), rollup.to(),
                        rollup.densityRows(), rollup.edgeRows(), rollup.odRows(),
                        rollup.elapsedMs()));
    }

    /**
     * Clears only what resolution produced.
     *
     * <p>Still deliberately not {@code TRUNCATE ... CASCADE}. V8 removed the
     * observations.vehicle_id foreign key that made a cascade destroy every ingested
     * event, but trajectory_events still references observations, so a cascade from
     * vehicles would reach them again. Ordered DELETEs respect the constraints, and the
     * sequences are reset so rebuilt ids start from 1.
     */
    private void clearResolvedIdentities() {
        jdbcTemplate.update("DELETE FROM trajectory_events");
        jdbcTemplate.update("DELETE FROM vehicles");
        jdbcTemplate.update("ALTER SEQUENCE vehicles_id_seq RESTART WITH 1");
        jdbcTemplate.update("ALTER SEQUENCE trajectory_events_id_seq RESTART WITH 1");
    }

    /** Covers the full span of observations, since a rebuild can move any of them. */
    private RollupService.RollupSummary recomputeAnalytics() {
        java.sql.Timestamp lo = jdbcTemplate.queryForObject(
                "SELECT min(first_seen_at) FROM observations", java.sql.Timestamp.class);
        java.sql.Timestamp hi = jdbcTemplate.queryForObject(
                "SELECT max(first_seen_at) FROM observations", java.sql.Timestamp.class);
        java.time.Instant now = java.time.Instant.now();
        return rollups.recompute(
                lo == null ? now.minusSeconds(3600) : lo.toInstant(),
                hi == null ? now : hi.toInstant());
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
