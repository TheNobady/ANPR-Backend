package com.anpr.backend.ingest;

/**
 * Normalises an OCR plate string into the form used for matching. The original
 * OCR text is always stored untouched alongside it.
 */
public final class PlateCanonicaliser {

    private static final String COUNTRY_PREFIX = "IND";

    private PlateCanonicaliser() {
    }

    /** Uppercases, strips spaces and hyphens, then drops a leading {@code IND} country prefix. */
    public static String canonicalise(String plateText) {
        if (plateText == null) {
            return null;
        }
        String canonical = plateText.toUpperCase()
                .replace(" ", "")
                .replace("-", "")
                .replace("–", "")   // en dash, seen in some OCR output
                .trim();
        if (canonical.startsWith(COUNTRY_PREFIX) && canonical.length() > COUNTRY_PREFIX.length()) {
            canonical = canonical.substring(COUNTRY_PREFIX.length());
        }
        return canonical.isEmpty() ? null : canonical;
    }
}
