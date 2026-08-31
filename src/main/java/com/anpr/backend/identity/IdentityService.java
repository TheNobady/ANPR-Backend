package com.anpr.backend.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gate, resolve, persist. The single entry point both the live queue and the batch
 * rebuild go through, so the two paths cannot drift apart.
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    private final CandidateGateway gateway;
    private final IdentityResolver resolver;
    private final IdentityWriter writer;

    public IdentityService(CandidateGateway gateway, IdentityResolver resolver, IdentityWriter writer) {
        this.gateway = gateway;
        this.resolver = resolver;
        this.writer = writer;
    }

    /**
     * What resolution did with one observation, for whatever runs after it.
     *
     * @param vehicleId the vehicle the observation ended up on
     */
    public record Outcome(long vehicleId, ObservationFacts facts, ResolutionResult result) {
        public boolean isNewVehicle() {
            return result.decision() instanceof ResolutionDecision.NewVehicle;
        }

        public Double linkConfidence() {
            return result.decision() instanceof ResolutionDecision.Link link
                    ? link.linkConfidence() : null;
        }
    }

    /** @return the outcome, or null if the observation could not be processed */
    public Outcome process(long observationId) {
        ObservationFacts facts = gateway.factsFor(observationId);
        if (facts == null) {
            log.warn("identity: observation {} not found", observationId);
            return null;
        }
        List<CandidateVehicle> candidates = gateway.candidatesFor(observationId);
        ResolutionResult result = resolver.resolve(facts, candidates);

        long vehicleId = result.decision() instanceof ResolutionDecision.Link link
                ? writer.applyLink(facts, link)
                : writer.applyNewVehicle(facts);
        return new Outcome(vehicleId, facts, result);
    }
}
