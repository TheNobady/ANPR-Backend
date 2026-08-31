package com.anpr.backend.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/** Rejected payloads are kept, never dropped -- they are the only record of a bad node. */
@Component
public class IngestRejectStore {

    private static final Logger log = LoggerFactory.getLogger(IngestRejectStore.class);

    /** Guards the rejects table against a node streaming enormous bodies. */
    private static final int MAX_RAW_BODY_CHARS = 64 * 1024;

    private static final String INSERT =
            "INSERT INTO ingest_rejects (raw_payload, reason) VALUES (CAST(? AS jsonb), ?)";

    private static final String SELECT_RECENT = """
            SELECT id, reason, received_at, raw_payload
              FROM ingest_rejects
             ORDER BY received_at DESC, id DESC
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IngestRejectStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the rejection in its own transaction so it survives whatever else the
     * request does. A body that is not valid JSON is wrapped in an envelope so the
     * {@code jsonb} column can still hold it verbatim.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String rawBody, String reason) {
        try {
            jdbcTemplate.update(INSERT, toJsonb(rawBody, reason), reason);
        } catch (RuntimeException e) {
            // Never let bookkeeping mask the rejection itself.
            log.error("Failed to persist ingest reject (reason='{}')", reason, e);
        }
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM ingest_rejects", Long.class);
        return count == null ? 0L : count;
    }

    public List<IngestRejectRecord> findRecent(int limit) {
        return jdbcTemplate.query(SELECT_RECENT, rejectRowMapper(), limit);
    }

    private RowMapper<IngestRejectRecord> rejectRowMapper() {
        return (rs, rowNum) -> {
            String payload = rs.getString("raw_payload");
            Timestamp receivedAt = rs.getTimestamp("received_at");
            return new IngestRejectRecord(
                    rs.getLong("id"),
                    rs.getString("reason"),
                    receivedAt == null ? null : receivedAt.toInstant(),
                    extractEventId(payload),
                    payload);
        };
    }

    private String extractEventId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode eventId = node.get("event_id");
            return eventId == null || eventId.isNull() ? null : eventId.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String toJsonb(String rawBody, String reason) {
        String body = rawBody == null ? "" : rawBody;
        if (body.length() > MAX_RAW_BODY_CHARS) {
            body = body.substring(0, MAX_RAW_BODY_CHARS);
        }
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (parsed != null && !parsed.isMissingNode()) {
                return objectMapper.writeValueAsString(parsed);
            }
        } catch (Exception ignored) {
            // Falls through to the envelope below.
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("_unparseable", true);
        envelope.put("_reason", reason);
        envelope.put("_raw_body", body);
        return envelope.toString();
    }
}
