package com.anpr.backend.identity;

/**
 * Turns a cosine similarity into a score contribution.
 *
 * <p>Isolated behind this interface on purpose. The shipped implementation is a
 * hand-set step function; the intended replacement is a likelihood ratio fitted to
 * labelled same/different pairs, which is a drop-in swap at this seam.
 */
public interface AppearanceScorer {

    /**
     * @param cosineSimilarity similarity in [-1, 1], or null when either side has no
     *                         embedding -- a missing embedding is not evidence either way
     */
    double score(Double cosineSimilarity);
}
