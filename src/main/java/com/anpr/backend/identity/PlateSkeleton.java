package com.anpr.backend.identity;

/**
 * Collapses every OCR-confusable class to a single representative, so two reads of the
 * same physical plate that differ only by a confusable glyph produce the same key.
 *
 * <p>O→0, I→1, B→8, S→5, Z→2, G→6. The direction is arbitrary but must match the
 * {@code translate()} call in V7, which backfills the column for existing rows.
 */
public final class PlateSkeleton {

    private static final char[] FROM = {'O', 'I', 'B', 'S', 'Z', 'G'};
    private static final char[] TO = {'0', '1', '8', '5', '2', '6'};

    private PlateSkeleton() {
    }

    /** @return null for a null or empty input, so a missing plate stays missing */
    public static String of(String plateCanonical) {
        if (plateCanonical == null || plateCanonical.isEmpty()) {
            return null;
        }
        char[] chars = plateCanonical.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = fold(chars[i]);
        }
        return new String(chars);
    }

    /** The representative character for {@code c}'s confusable class, or {@code c} itself. */
    public static char fold(char c) {
        for (int i = 0; i < FROM.length; i++) {
            if (FROM[i] == c) {
                return TO[i];
            }
        }
        return c;
    }

    /** True when the two characters differ but belong to the same confusable class. */
    public static boolean confusable(char a, char b) {
        return a != b && fold(a) == fold(b);
    }
}
