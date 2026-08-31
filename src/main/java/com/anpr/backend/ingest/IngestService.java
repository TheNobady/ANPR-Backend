package com.anpr.backend.ingest;

import com.anpr.backend.camera.Camera;
import com.anpr.backend.camera.CameraService;
import com.anpr.backend.identity.IdentityQueue;
import com.anpr.backend.identity.PlateSkeleton;
import com.anpr.backend.observation.ObservationInsert;
import com.anpr.backend.observation.ObservationWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The ingest fast path: parse, validate, insert, respond.
 *
 * <p>Nodes give up after two seconds and drop the event, so nothing slow may happen
 * before the response is written. Everything expensive belongs in the Phase 3 async
 * stage, marked below.
 *
 * <p>This class is deliberately not {@code @Transactional}: camera registration, the
 * observation insert and reject bookkeeping each own their transaction, so a rejection
 * is durable even though the request fails.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** The perception model's embedding width. Anything else is a version mismatch, not a dent. */
    public static final int EMBEDDING_DIMENSION = 512;

    private final ObjectMapper objectMapper;
    private final CameraService cameraService;
    private final ObservationWriter observationWriter;
    private final IngestRejectStore rejectStore;
    private final IdentityQueue identityQueue;

    public IngestService(ObjectMapper objectMapper,
                         CameraService cameraService,
                         ObservationWriter observationWriter,
                         IngestRejectStore rejectStore,
                         IdentityQueue identityQueue) {
        this.objectMapper = objectMapper;
        this.cameraService = cameraService;
        this.observationWriter = observationWriter;
        this.rejectStore = rejectStore;
        this.identityQueue = identityQueue;
    }

    /**
     * @param rawBody the request body exactly as the node sent it -- kept as text so a
     *                payload that fails to parse can still be stored in {@code ingest_rejects}
     * @throws IngestRejectedException when the event cannot be accepted; the rejection
     *                                 has already been persisted when this is thrown
     */
    public IngestAcceptedResponse ingest(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw reject(rawBody, null, RejectReason.EMPTY_BODY);
        }

        EventPayload payload = parse(rawBody);

        requireText(rawBody, payload.eventId(), payload, RejectReason.MISSING_EVENT_ID);
        requireText(rawBody, payload.cameraId(), payload, RejectReason.MISSING_CAMERA_ID);
        requireText(rawBody, payload.trackletId(), payload, RejectReason.MISSING_TRACKLET_ID);

        Instant firstSeen = payload.timestampFirstSeen();
        Instant lastSeen = payload.timestampLastSeen();
        if (firstSeen == null) {
            throw reject(rawBody, payload.eventId(), RejectReason.MISSING_TIMESTAMP_FIRST_SEEN);
        }
        if (lastSeen == null) {
            throw reject(rawBody, payload.eventId(), RejectReason.MISSING_TIMESTAMP_LAST_SEEN);
        }
        if (lastSeen.isBefore(firstSeen)) {
            throw reject(rawBody, payload.eventId(), RejectReason.NEGATIVE_DWELL);
        }
        int dwellMs = (int) Math.min(Integer.MAX_VALUE, Duration.between(firstSeen, lastSeen).toMillis());

        String embeddingLiteral = embeddingLiteral(rawBody, payload);

        resolveCamera(rawBody, payload);

        EventPayload.Plate plate = payload.plate();
        EventPayload.Vehicle vehicle = payload.vehicle();
        EventPayload.CameraLocation location = payload.cameraLocation();

        ObservationInsert observation = new ObservationInsert(
                payload.eventId(),
                payload.cameraId(),
                payload.trackletId(),
                firstSeen,
                lastSeen,
                dwellMs,
                plate == null ? null : plate.text(),
                plate == null ? null : plate.confidence(),
                plate == null ? null : toFloatArray(plate.charConfidences()),
                plate == null ? null : toStringArray(plate.rawOcrReads()),
                plate == null ? null : PlateCanonicaliser.canonicalise(plate.text()),
                plate == null ? null
                        : PlateSkeleton.of(PlateCanonicaliser.canonicalise(plate.text())),
                vehicle == null ? null : vehicle.type(),
                vehicle == null ? null : vehicle.typeConfidence(),
                vehicle == null ? null : vehicle.colour(),
                vehicle == null ? null : vehicle.colourConfidence(),
                embeddingLiteral,
                vehicle == null ? null : toIntArray(vehicle.bboxLast()),
                payload.detectionConfidence(),
                payload.nodeMode(),
                payload.snapshotUrl(),
                location == null ? null : location.lat(),
                location == null ? null : location.lon());

        Optional<Long> insertedId = observationWriter.insert(observation);

        if (insertedId.isPresent()) {
            cameraService.touchLastEventAt(payload.cameraId(), firstSeen);
            // Identity resolution runs off the request thread. The offer is non-blocking
            // and a full queue drops the id rather than slowing ingest down; anything
            // dropped is recovered by POST /api/identity/rebuild.
            identityQueue.submit(insertedId.get());
        } else {
            log.debug("Duplicate tracklet ignored: camera='{}' tracklet='{}' event='{}'",
                    payload.cameraId(), payload.trackletId(), payload.eventId());
        }

        return IngestAcceptedResponse.of(payload.eventId());
    }

    // ------------------------------------------------------------------ parsing

    private EventPayload parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, EventPayload.class);
        } catch (MismatchedInputException e) {
            throw reject(rawBody, recoverEventId(rawBody),
                    RejectReason.INVALID_FIELD_FORMAT + fieldSuffix(e));
        } catch (JsonProcessingException e) {
            throw reject(rawBody, recoverEventId(rawBody), RejectReason.MALFORMED_JSON);
        }
    }

    /** Best-effort event id for the response when the payload itself would not bind. */
    private String recoverEventId(String rawBody) {
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            JsonNode eventId = node.get("event_id");
            return eventId == null || eventId.isNull() ? null : eventId.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String fieldSuffix(MismatchedInputException e) {
        if (e.getPath() == null || e.getPath().isEmpty()) {
            return "";
        }
        StringJoiner path = new StringJoiner(".");
        e.getPath().forEach(ref -> {
            if (ref.getFieldName() != null) {
                path.add(ref.getFieldName());
            }
        });
        return path.length() == 0 ? "" : ":" + path;
    }

    // --------------------------------------------------------------- validation

    private void requireText(String rawBody, String value, EventPayload payload, String reason) {
        if (value == null || value.isBlank()) {
            throw reject(rawBody, payload.eventId(), reason);
        }
    }

    /** Null embedding is fine -- plenty of events carry only a plate. A wrong width is not. */
    private String embeddingLiteral(String rawBody, EventPayload payload) {
        EventPayload.Vehicle vehicle = payload.vehicle();
        if (vehicle == null || vehicle.embedding() == null) {
            return null;
        }
        List<Float> embedding = vehicle.embedding();
        if (embedding.size() != EMBEDDING_DIMENSION) {
            log.warn("Rejecting event '{}' from camera '{}': embedding had {} dimensions, expected {}",
                    payload.eventId(), payload.cameraId(), embedding.size(), EMBEDDING_DIMENSION);
            throw reject(rawBody, payload.eventId(), RejectReason.EMBEDDING_DIMENSION_MISMATCH);
        }
        StringBuilder literal = new StringBuilder(EMBEDDING_DIMENSION * 8).append('[');
        for (int i = 0; i < embedding.size(); i++) {
            Float value = embedding.get(i);
            if (value == null || value.isNaN() || value.isInfinite()) {
                throw reject(rawBody, payload.eventId(), RejectReason.EMBEDDING_DIMENSION_MISMATCH);
            }
            if (i > 0) {
                literal.append(',');
            }
            literal.append(value.floatValue());
        }
        return literal.append(']').toString();
    }

    /**
     * Ensures the camera row exists. A known camera's surveyed position always wins;
     * the node-reported position is kept on the observation only.
     */
    private void resolveCamera(String rawBody, EventPayload payload) {
        EventPayload.CameraLocation location = payload.cameraLocation();
        if (location != null && location.hasCoordinates() && !isPlausible(location)) {
            throw reject(rawBody, payload.eventId(), RejectReason.INVALID_CAMERA_LOCATION);
        }

        Optional<Camera> known = cameraService.find(payload.cameraId());
        if (known.isPresent()) {
            if (location != null && location.hasCoordinates()) {
                cameraService.warnIfLocationDrifts(known.get(), location.lat(), location.lon());
            }
            return;
        }

        if (location == null || !location.hasCoordinates()) {
            throw reject(rawBody, payload.eventId(), RejectReason.UNKNOWN_CAMERA_NO_LOCATION);
        }
        cameraService.autoRegister(payload.cameraId(), location.lat(), location.lon(), location.name());
    }

    private boolean isPlausible(EventPayload.CameraLocation location) {
        return location.lat() >= -90 && location.lat() <= 90
                && location.lon() >= -180 && location.lon() <= 180;
    }

    // ------------------------------------------------------------------ rejects

    /** Persists the rejection, then returns the exception for the caller to throw. */
    private IngestRejectedException reject(String rawBody, String eventId, String reason) {
        rejectStore.record(rawBody, reason);
        log.warn("Rejected event '{}': {}", eventId, reason);
        return new IngestRejectedException(eventId, reason);
    }

    // ------------------------------------------------------------- conversions

    private static float[] toFloatArray(List<Float> values) {
        if (values == null) {
            return null;
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Float v = values.get(i);
            out[i] = v == null ? Float.NaN : v;
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        if (values == null) {
            return null;
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Integer v = values.get(i);
            out[i] = v == null ? 0 : v;
        }
        return out;
    }

    private static String[] toStringArray(List<String> values) {
        return values == null ? null : values.toArray(new String[0]);
    }
}
