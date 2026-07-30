package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

public class WebhookInboundEventRepository {
    private static final RowMapper<WebhookInboundEventDto> ROW_MAPPER = WebhookInboundEventRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public WebhookInboundEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WebhookInboundEventDto insert(
        Long subscriptionId,
        String eventType,
        String requestToken,
        String noteToken,
        String eventTimestamp,
        boolean signatureValid,
        boolean cacheSynced,
        String syncMessage,
        String rawPayload
    ) {
        String receivedAt = Instant.now().toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                """
                INSERT INTO webhook_inbound_event (
                    subscription_id, event_type, request_token, note_token, event_timestamp,
                    received_at, signature_valid, cache_synced, sync_message, raw_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new String[] {"id"}
            );
            ps.setObject(1, subscriptionId);
            ps.setString(2, eventType);
            ps.setString(3, requestToken);
            ps.setString(4, noteToken);
            ps.setString(5, eventTimestamp);
            ps.setString(6, receivedAt);
            ps.setInt(7, signatureValid ? 1 : 0);
            ps.setInt(8, cacheSynced ? 1 : 0);
            ps.setString(9, syncMessage);
            ps.setString(10, rawPayload);
            return ps;
        }, keyHolder);

        Long id = keyHolder.getKey() != null ? keyHolder.getKey().longValue() : null;
        return new WebhookInboundEventDto(
            id,
            subscriptionId,
            eventType,
            requestToken,
            noteToken,
            eventTimestamp,
            receivedAt,
            signatureValid,
            cacheSynced,
            syncMessage,
            rawPayload
        );
    }

    public List<WebhookInboundEventDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT id, subscription_id, event_type, request_token, note_token, event_timestamp,
                   received_at, signature_valid, cache_synced, sync_message, raw_payload
            FROM webhook_inbound_event
            ORDER BY id DESC
            """,
            ROW_MAPPER
        );
    }

    private static WebhookInboundEventDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookInboundEventDto(
            rs.getObject("id", Long.class),
            rs.getObject("subscription_id", Long.class),
            rs.getString("event_type"),
            rs.getString("request_token"),
            rs.getString("note_token"),
            rs.getString("event_timestamp"),
            rs.getString("received_at"),
            rs.getObject("signature_valid", Integer.class) != null && rs.getInt("signature_valid") == 1,
            rs.getObject("cache_synced", Integer.class) != null && rs.getInt("cache_synced") == 1,
            rs.getString("sync_message"),
            rs.getString("raw_payload")
        );
    }
}
