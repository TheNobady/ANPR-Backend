package com.anpr.backend.identity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which vehicle an observation belongs to.
 *
 * <p>Pure: it takes the observation's facts and a list of pre-gated candidates and returns
 * a decision. It reads nothing and writes nothing, so its behaviour can be pinned down in
 * unit tests without a database anywhere near it.
 */
@Component
public class IdentityResolver {

    private final IdentityProperties properties;
    private final AppearanceScorer appearanceScorer;

    public IdentityResolver(IdentityProperties properties, AppearanceScorer appearanceScorer) {
        this.properties = properties;
        this.appearanceScorer = appearanceScorer;
    }

    public ResolutionResult resolve(ObservationFacts observation, List<CandidateVehicle> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ResolutionResult.of(new ResolutionDecision.NewVehicle("no_candidates", null));
        }

        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        List<RejectedCandidate> rejected = new ArrayList<>();

        for (CandidateVehicle candidate : candidates) {
            ScoredCandidate result = score(observation, candidate);
            if (result.vetoed()) {
                // A veto means the plates are too far apart to be the same registration, so
                // by definition it is not one of the plate-matching rejections worth reporting.
                continue;
            }
            scored.add(result);
        }
        if (scored.isEmpty()) {
            return ResolutionResult.of(
                    new ResolutionDecision.NewVehicle("all_candidates_vetoed", null));
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::total).reversed());
        ScoredCandidate best = scored.get(0);
        Double runnerUpTotal = scored.size() > 1 ? scored.get(1).total() : null;

        boolean belowThreshold = best.total() < properties.getThreshold();
        boolean ambiguous = !belowThreshold && runnerUpTotal != null
                && best.total() - runnerUpTotal < properties.getAmbiguityMargin();

        if (belowThreshold || ambiguous) {
            // Nothing was linked, so every plate-matching candidate is a rejection.
            for (ScoredCandidate s : scored) {
                report(rejected, s, ambiguous ? RejectionReason.ambiguous_margin : reasonFor(s));
            }
            String why = belowThreshold ? "below_threshold" : "ambiguous";
            return new ResolutionResult(
                    new ResolutionDecision.NewVehicle(why, best.total()), List.copyOf(rejected));
        }

        // A link was made. Report the runners-up only when they were close enough to have
        // been contenders -- a candidate that simply scored worse was decided on merit, not
        // rejected for a reason.
        for (int i = 1; i < scored.size(); i++) {
            ScoredCandidate s = scored.get(i);
            if (best.total() - s.total() < properties.getAmbiguityMargin()) {
                report(rejected, s, RejectionReason.ambiguous_margin);
            } else if (s.total() < properties.getThreshold()) {
                report(rejected, s, reasonFor(s));
            }
        }

        ResolutionDecision.Link link = new ResolutionDecision.Link(
                best.candidate().vehicleId(),
                best.total(),
                confidence(best.total()),
                best.breakdown().withRunnerUp(runnerUpTotal),
                best.candidate().lastCameraId(),
                best.candidate().edgeId());
        return new ResolutionResult(link, List.copyOf(rejected));
    }

    /**
     * An exact or skeleton plate match killed by a negative appearance score is the clone
     * signature; anything else that fell short is just short.
     */
    private RejectionReason reasonFor(ScoredCandidate scored) {
        ScoreBreakdown b = scored.breakdown();
        boolean plateMatched = "exact".equals(b.plateRule()) || "skeleton".equals(b.plateRule());
        return plateMatched && b.appearance() < 0
                ? RejectionReason.appearance_mismatch
                : RejectionReason.below_threshold;
    }

    /** Only plate-matching candidates are reported -- the rest carry no signal. */
    private void report(List<RejectedCandidate> into, ScoredCandidate scored, RejectionReason reason) {
        ScoreBreakdown b = scored.breakdown();
        if (!"exact".equals(b.plateRule()) && !"skeleton".equals(b.plateRule())) {
            return;
        }
        CandidateVehicle c = scored.candidate();
        into.add(new RejectedCandidate(
                c.vehicleId(), c.canonicalPlate(), c.lastCameraId(), reason, b.plateRule(),
                scored.total(), b.appearance(), c.cosineSimilarity(), c.elapsedSeconds(),
                c.viaEdge()));
    }

    /** Logistic centred on the acceptance threshold, so a bare pass reads as ~0.5. */
    public double confidence(double total) {
        return 1.0 / (1.0 + Math.exp(-(total - properties.getThreshold()) / 2.0));
    }

    ScoredCandidate score(ObservationFacts observation, CandidateVehicle candidate) {
        PlateScore plate = scorePlate(observation, candidate);
        if (plate.veto()) {
            return ScoredCandidate.veto(candidate);
        }
        double travel = scoreTravelTime(candidate);
        double appearance = appearanceScorer.score(candidate.cosineSimilarity());
        double total = plate.score() + travel + appearance;

        ScoreBreakdown breakdown = new ScoreBreakdown(
                plate.score(), travel, appearance, total, plate.rule(), plate.distance(),
                candidate.cosineSimilarity(), candidate.elapsedSeconds(),
                candidate.typicalSeconds(), null);
        return new ScoredCandidate(candidate, total, breakdown, false);
    }

    private record PlateScore(double score, String rule, Double distance, boolean veto) {
    }

    /**
     * A missing plate on either side scores zero and vetoes nothing -- the node still sent
     * the event because the embedding was worth having, and treating silence as evidence
     * against a match would throw that away.
     */
    private PlateScore scorePlate(ObservationFacts observation, CandidateVehicle candidate) {
        IdentityProperties.Scoring s = properties.getScoring();
        String observed = observation.plateCanonical();
        String known = candidate.canonicalPlate();
        if (observed == null || observed.isEmpty() || known == null || known.isEmpty()) {
            return new PlateScore(0.0, "no_plate", null, false);
        }
        if (observed.equals(known)) {
            return new PlateScore(s.getPlateExact(), "exact", 0.0, false);
        }
        if (observation.plateSkeleton() != null
                && observation.plateSkeleton().equals(candidate.plateSkeleton())) {
            return new PlateScore(s.getPlateSkeleton(), "skeleton", null, false);
        }

        double distance = ConfusionEditDistance.between(
                observed, known, observation.plateCharConfidences());
        if (distance <= 1.0) {
            return new PlateScore(s.getPlateDistance1(), "distance_1", distance, false);
        }
        if (distance <= s.getPlateVetoDistance()) {
            return new PlateScore(s.getPlateDistance2(), "distance_2", distance, false);
        }
        // Two plates this far apart are different vehicles, whatever the other signals say.
        return new PlateScore(0.0, "veto", distance, true);
    }

    /**
     * Log-normal around the edge's expected travel time: arriving when expected is strong
     * evidence, arriving far too early or too late is not. Peaks at travelWeight.
     */
    private double scoreTravelTime(CandidateVehicle candidate) {
        if (!candidate.viaEdge() || candidate.elapsedSeconds() <= 0) {
            return 0.0;
        }
        IdentityProperties.Scoring s = properties.getScoring();
        double mu = Math.log(candidate.typicalSeconds());
        double sigma = s.getTravelSigma();
        double delta = Math.log(candidate.elapsedSeconds()) - mu;
        return s.getTravelWeight() * Math.exp(-(delta * delta) / (2 * sigma * sigma));
    }
}
