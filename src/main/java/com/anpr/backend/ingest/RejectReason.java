package com.anpr.backend.ingest;

/** The reason strings written to {@code ingest_rejects.reason} and returned to the node. */
public final class RejectReason {

    public static final String MALFORMED_JSON = "malformed_json";
    public static final String INVALID_FIELD_FORMAT = "invalid_field_format";
    public static final String EMPTY_BODY = "empty_body";
    public static final String MISSING_EVENT_ID = "missing_event_id";
    public static final String MISSING_CAMERA_ID = "missing_camera_id";
    public static final String MISSING_TRACKLET_ID = "missing_tracklet_id";
    public static final String MISSING_TIMESTAMP_FIRST_SEEN = "missing_timestamp_first_seen";
    public static final String MISSING_TIMESTAMP_LAST_SEEN = "missing_timestamp_last_seen";
    public static final String NEGATIVE_DWELL = "negative_dwell";
    public static final String EMBEDDING_DIMENSION_MISMATCH = "embedding_dimension_mismatch";
    public static final String UNKNOWN_CAMERA_NO_LOCATION = "unknown_camera_no_location";
    public static final String INVALID_CAMERA_LOCATION = "invalid_camera_location";
    public static final String PERSISTENCE_FAILURE = "persistence_failure";

    private RejectReason() {
    }
}
