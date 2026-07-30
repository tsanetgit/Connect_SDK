package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class WebhookSubscriptionRepository {
    private static final RowMapper<WebhookSubscriptionDto> ROW_MAPPER = WebhookSubscriptionRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public WebhookSubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<WebhookSubscriptionDto> subscriptions) {
        if (subscriptions.isEmpty()) {
            return;
        }

        String fetchedAt = Instant.now().toString();
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO webhook_subscription (
                id, callback_url, event_types, active, created_at, updated_at, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                callback_url = excluded.callback_url,
                event_types = excluded.event_types,
                active = excluded.active,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                fetched_at = excluded.fetched_at
            """,
            subscriptions,
            subscriptions.size(),
            (ps, subscription) -> {
                ps.setObject(1, subscription.id());
                ps.setString(2, subscription.callbackUrl());
                ps.setString(3, subscription.eventTypes());
                ps.setObject(4, subscription.active() != null && subscription.active() ? 1 : 0);
                ps.setString(5, subscription.createdAt());
                ps.setString(6, subscription.updatedAt());
                ps.setString(7, fetchedAt);
            }
        );
    }

    public List<WebhookSubscriptionDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT id, callback_url, event_types, active, created_at, updated_at
            FROM webhook_subscription
            ORDER BY id
            """,
            ROW_MAPPER
        );
    }

    public void saveSecret(Long id, String secret) {
        if (id == null || secret == null || secret.isBlank()) {
            return;
        }
        jdbcTemplate.update(
            "UPDATE webhook_subscription SET secret = ? WHERE id = ?",
            secret,
            id
        );
    }

    public List<SecretRow> findVerificationSecrets() {
        return jdbcTemplate.query(
            """
            SELECT id, secret
            FROM webhook_subscription
            WHERE secret IS NOT NULL AND TRIM(secret) <> ''
            ORDER BY id
            """,
            (rs, rowNum) -> new SecretRow(rs.getLong("id"), rs.getString("secret"))
        );
    }

    public record SecretRow(long subscriptionId, String secret) {
    }

    private static WebhookSubscriptionDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer active = rs.getObject("active", Integer.class);
        return new WebhookSubscriptionDto(
            rs.getObject("id", Long.class),
            rs.getString("callback_url"),
            rs.getString("event_types"),
            active != null && active == 1,
            rs.getString("created_at"),
            rs.getString("updated_at")
        );
    }
}
