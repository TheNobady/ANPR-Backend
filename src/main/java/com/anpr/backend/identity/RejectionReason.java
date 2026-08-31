package com.anpr.backend.identity;

/** Why a gated candidate did not become the link. */
public enum RejectionReason {
    /** The best candidate's total fell short of {@code identity.threshold}. */
    below_threshold,
    /** Two candidates were too close to separate, so neither was trusted. */
    ambiguous_margin,
    /** Plate distance beyond the veto: these are different registrations. */
    plate_veto,
    /**
     * The plate matched exactly or by skeleton, but appearance said otherwise.
     *
     * <p>The interesting one. A plate that matches on a vehicle that does not look the same
     * is the signature of a cloned registration, which is why the resolver's rejections are
     * returned rather than discarded.
     */
    appearance_mismatch
}
