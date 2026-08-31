package com.anpr.backend.alert;

public final class AlertType {
    public static final String BLACKLIST_MATCH = "blacklist_match";
    public static final String IMPOSSIBLE_SPEED = "impossible_speed";
    public static final String RESTRICTED_ZONE_ENTRY = "restricted_zone_entry";
    public static final String PLATE_APPEARANCE_MISMATCH = "plate_appearance_mismatch";

    private AlertType() {
    }
}
