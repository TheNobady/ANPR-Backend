package com.anpr.backend.identity;

import org.springframework.stereotype.Component;

/** Step function over cosine similarity. Deliberately a placeholder -- see {@link AppearanceScorer}. */
@Component
public class PiecewiseAppearanceScorer implements AppearanceScorer {

    private final IdentityProperties.Scoring scoring;

    public PiecewiseAppearanceScorer(IdentityProperties properties) {
        this.scoring = properties.getScoring();
    }

    @Override
    public double score(Double cosineSimilarity) {
        if (cosineSimilarity == null) {
            return 0.0;
        }
        double s = cosineSimilarity;
        if (s >= scoring.getAppearanceStrongThreshold()) {
            return scoring.getAppearanceStrong();
        }
        if (s >= scoring.getAppearanceWeakThreshold()) {
            return scoring.getAppearanceWeak();
        }
        if (s >= scoring.getAppearanceNeutralThreshold()) {
            return scoring.getAppearanceNeutral();
        }
        return scoring.getAppearancePenalty();
    }
}
