package com.anpr.backend.identity;

/** A candidate with its score, or a note that it was vetoed. */
public record ScoredCandidate(CandidateVehicle candidate, double total, ScoreBreakdown breakdown,
                              boolean vetoed) {

    static ScoredCandidate veto(CandidateVehicle candidate) {
        return new ScoredCandidate(candidate, Double.NEGATIVE_INFINITY, null, true);
    }
}
