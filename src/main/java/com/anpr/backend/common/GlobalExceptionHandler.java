package com.anpr.backend.common;

import com.anpr.backend.ingest.IngestRejectedException;
import com.anpr.backend.ingest.IngestRejectedResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * An ingest rejection is a normal, expected outcome -- the payload has already been
     * stored in {@code ingest_rejects} by the time this runs. It gets its own envelope
     * because the perception nodes parse it.
     */
    @ExceptionHandler(IngestRejectedException.class)
    public ResponseEntity<IngestRejectedResponse> onIngestRejected(IngestRejectedException e) {
        return ResponseEntity.badRequest()
                .body(IngestRejectedResponse.of(e.getEventId(), e.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> onBadRequest(Exception e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("bad_request", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> onUnsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of("unsupported_media_type",
                        "Content-Type must be application/json", request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> onMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("method_not_allowed", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> onNotFound(NoResourceFoundException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "No handler for " + request.getRequestURI(),
                        request.getRequestURI()));
    }

    /**
     * A deliberately signalled status -- a missing vehicle, say. Must be handled explicitly:
     * without this the catch-all below turns every intentional 404 into a 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> onResponseStatus(ResponseStatusException e,
                                                     HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        String code = status == null
                ? "error"
                : status.name().toLowerCase(java.util.Locale.ROOT);
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiError.of(code, e.getReason(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "Unexpected server error",
                        request.getRequestURI()));
    }
}
