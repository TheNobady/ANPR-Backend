package com.anpr.backend.alert;

import java.time.Instant;
import java.util.Map;

/** An alert about to be written. */
record NewAlert(
        String alertType,
        Severity severity,
        Long vehicleId,
        Long relatedVehicleId,
        Long observationId,
        String cameraId,
        Instant occurredAt,
        String message,
        Map<String, Object> metadata
) {}
