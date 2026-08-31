package com.anpr.backend.identity;

/**
 * Levenshtein distance where a substitution inside a confusable pair is cheap.
 *
 * <p>A substitution within {0,O} {1,I} {8,B} {5,S} {2,Z} {6,G} costs
 * {@value #CONFUSABLE_SUBSTITUTION_COST}; every other substitution, insertion or
 * deletion costs {@value #FULL_COST}.
 *
 * <p>Where per-character confidences are available the cost of a mismatch is scaled by
 * the reader's <em>confidence</em> in the character it reported. Disagreeing with a
 * character the reader was unsure of is weak evidence that these are different vehicles
 * -- it probably just misread it -- so it costs little. Disagreeing with one it was
 * certain of is strong evidence, so it costs full price.
 *
 * <p>Note: the Phase 3 brief specified this weight as {@code (1 - char_confidence)} while
 * describing the behaviour implemented here ("a mismatch on a character the OCR was
 * unsure about costs less"). Those two disagree -- {@code 1 - confidence} makes an
 * unreliable character the <em>most</em> expensive to differ on, which would penalise
 * exactly the OCR failures this system exists to see through. The described behaviour is
 * implemented and the literal formula is not.
 */
public final class ConfusionEditDistance {

    public static final double CONFUSABLE_SUBSTITUTION_COST = 0.5;
    public static final double FULL_COST = 1.0;

    /** Floor on the weight, so even a zero-confidence character is not free to differ. */
    private static final double MIN_WEIGHT = 0.2;

    private ConfusionEditDistance() {
    }

    public static double between(String a, String b) {
        return between(a, b, null);
    }

    /**
     * @param charConfidences per-character confidences for {@code a}, or null for unweighted.
     *                        Indexed against {@code a}; entries beyond its length are ignored.
     */
    public static double between(String a, String b, float[] charConfidences) {
        if (a == null || b == null) {
            return Double.POSITIVE_INFINITY;
        }
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m * FULL_COST;
        }
        if (m == 0) {
            return n * FULL_COST;
        }

        double[] previous = new double[m + 1];
        double[] current = new double[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j * FULL_COST;
        }

        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            double weight = weightFor(charConfidences, i - 1);
            current[0] = i * FULL_COST;
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                double substitution;
                if (ca == cb) {
                    substitution = 0.0;
                } else {
                    double base = PlateSkeleton.confusable(ca, cb)
                            ? CONFUSABLE_SUBSTITUTION_COST
                            : FULL_COST;
                    substitution = base * weight;
                }
                current[j] = Math.min(
                        Math.min(current[j - 1] + FULL_COST, previous[j] + FULL_COST),
                        previous[j - 1] + substitution);
            }
            double[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[m];
    }

    /**
     * A mismatch on a character the OCR reported at confidence {@code c} costs {@code c}
     * of the base cost, floored so it never becomes free.
     */
    private static double weightFor(float[] charConfidences, int index) {
        if (charConfidences == null || index >= charConfidences.length) {
            return 1.0;
        }
        float confidence = charConfidences[index];
        if (Float.isNaN(confidence)) {
            return 1.0;
        }
        return Math.max(MIN_WEIGHT, Math.clamp(confidence, 0.0f, 1.0f));
    }
}
