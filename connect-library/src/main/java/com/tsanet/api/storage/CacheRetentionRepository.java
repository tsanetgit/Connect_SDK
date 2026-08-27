package com.tsanet.api.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Named queries backing {@link CacheRetention}'s sweep phases, isolated here so the
 * orchestration in {@code CacheRetention} stays free of inline SQL (review comment on
 * Connect_SDK#66).
 *
 * <p>Package-private, and not shaped like the sibling {@code *Repository} classes: it
 * wraps the single {@link Connection} the sweep already owns for its own multi-phase
 * transaction (explicit commit per phase, explicit rollback on failure), rather than a
 * {@link org.springframework.jdbc.core.JdbcTemplate} that checks out its own connection
 * per call. It only works called within that caller-managed transaction — not a
 * general-purpose repository.
 */
final class CacheRetentionRepository {

    private final Connection connection;

    CacheRetentionRepository(Connection connection) {
        this.connection = connection;
    }

    void setBusyTimeout(int millis) throws SQLException {
        try (Statement s = connection.createStatement()) {
            // busy_timeout only; journal_mode is persistent per-database and is the
            // store's own concern.
            s.execute("PRAGMA busy_timeout = " + millis);
        }
    }

    /** Terminal set: CLOSED + REJECTED; PENDINGACTION is deliberately not terminal. */
    List<String> findDoomedTokens(String cutoff) throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT token FROM collaboration_request WHERE status IN ('CLOSED','REJECTED')"
                        + " AND COALESCE(updated_at, fetched_at) < ?")) {
            ps.setString(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tokens.add(rs.getString(1));
                }
            }
        }
        return tokens;
    }

    int deleteByTokens(String table, String column, List<String> tokens) throws SQLException {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + in + ")")) {
            for (int i = 0; i < tokens.size(); i++) {
                ps.setString(i + 1, tokens.get(i));
            }
            return ps.executeUpdate();
        }
    }

    /** NOT IN is NULL-safe here: collaboration_request.token is NOT NULL UNIQUE. */
    int deleteOrphans(String table, String ageColumn, String cutoff) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + ageColumn + " < ?"
                        + " AND case_token NOT IN (SELECT token FROM collaboration_request)")) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        }
    }

    int deleteExpiredWebhookEvents(String cutoff) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM webhook_inbound_event WHERE received_at < ?")) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        }
    }
}
