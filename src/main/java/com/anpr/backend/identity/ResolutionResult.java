package com.anpr.backend.identity;

import java.util.List;

/**
 * What the resolver concluded, and what it turned down getting there.
 *
 * <p>The rejections used to be discarded. They are the entire basis for clone detection:
 * a candidate whose plate matched exactly but whose appearance did not is the observable
 * signature of two vehicles wearing one registration.
 */
public record ResolutionResult(ResolutionDecision decision, List<RejectedCandidate> rejected) {

    public static ResolutionResult of(ResolutionDecision decision) {
        return new ResolutionResult(decision, List.of());
    }
}
