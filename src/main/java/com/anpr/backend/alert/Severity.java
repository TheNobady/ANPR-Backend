package com.anpr.backend.alert;

/** Ordered so a fuzzy match can be reported one level below an exact one. */
public enum Severity {
    low, medium, high;

    public static Severity parse(String value, Severity fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** One step down, floored at {@link #low}. */
    public Severity downgraded() {
        return switch (this) {
            case high -> medium;
            case medium, low -> low;
        };
    }
}
