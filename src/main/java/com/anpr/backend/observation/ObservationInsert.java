package com.anpr.backend.observation;

import java.time.Instant;

/** A fully validated observation, ready to be written. */
public record ObservationInsert(
        String eventId,
        String cameraId,
        String trackletId,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int dwellMs,
        String plateText,
        Float plateConfidence,
        float[] plateCharConfidences,
        String[] plateRawReads,
        String plateCanonical,
        String plateSkeleton,
        String vehicleType,
        Float vehicleTypeConfidence,
        String vehicleColour,
        Float vehicleColourConfidence,
        /** pgvector literal, e.g. {@code [0.021,-0.114,...]}, or null. */
        String embeddingLiteral,
        int[] bboxLast,
        Float detectionConfidence,
        String nodeMode,
        String snapshotUrl,
        Double nodeReportedLat,
        Double nodeReportedLon
) {}
