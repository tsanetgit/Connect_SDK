package com.tsanet.api.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Age-based eviction for the SQLite cache this library owns (Connect_SDK#65). The
 * cache holds case content — collaboration summaries, note text, response details,
 * raw webhook payloads — and rows otherwise persist until the file is deleted. Safe
 * to evict because the store is a cache, not the source of truth: consumers re-fetch
 * from the Connect API by token.
 *
 * <p>Semantics (ported verbatim from the Fin/Intercom adapter's interim sweep, where
 * they were probed and red/green-tested — tsanetgit/Fin-Intercom_App#69):
 *
 * <ul>
 *   <li><b>Terminal cases only</b> ({@code CLOSED}/{@code REJECTED}) older than the
 *       window, age judged by {@code COALESCE(updated_at, fetched_at)}; {@code OPEN}
 *       cases never age out — unresolved is live state, not residue.</li>
 *   <li>Phase 1, one transaction: the doomed token set with its {@code case_note} /
 *       {@code case_response} / {@code attachment_config} /
 *       {@code attachment_forward_result} children, children before parents, chunked
 *       IN-lists. Phase 2: aged orphan children whose parent is already gone. Phase 3:
 *       {@code webhook_inbound_event} by {@code received_at}.</li>
 *   <li>Reference/config tables ({@code user_context}, {@code partner_selection},
 *       {@code collaboration_request_form}, {@code webhook_subscription}) untouched.</li>
 *   <li>Timestamps here are {@code Instant.toString()} (see the repositories), so a
 *       lexicographic compare against a same-format cutoff is correct to within
 *       fraction-length noise at the boundary — irrelevant at retention scale.</li>
 * </ul>
 *
 * <p>The library stays silent (no logging): the result carries the per-table counts
 * for the caller's audit line, and a mid-run failure throws
 * {@link RetentionSweepException} naming the failed phase with the counts from the
 * phases that already COMMITTED — a failed run can still have deleted rows, and the
 * caller's audit trail should say so. The failing phase's own deletes roll back.
 *
 * <p>Static on {@link JdbcTemplate}, like {@link DatabaseInitializer}: config (window
 * source, scheduling, enable/disable) is the consumer's concern.
 */
public final class CacheRetention {

    /** Chunk size for IN-list deletes: works on every sqlite build (no DELETE..LIMIT). */
    private static final int CHUNK = 500;

    private CacheRetention() {
    }

    /** Per-table deleted counts for one sweep run. */
    public record SweepResult(int cases, int notes, int responses, int attachConfigs,
            int attachForwards, int orphanNotes, int orphanResponses, int orphanAttachConfigs,
            int orphanAttachForwards, int events) {
        public int total() {
            return cases + notes + responses + attachConfigs + attachForwards
                    + orphanNotes + orphanResponses + orphanAttachConfigs + orphanAttachForwards + events;
        }
    }

    /** A mid-run failure: {@code phase} failed, {@code committed} is what already landed. */
    public static final class RetentionSweepException extends RuntimeException {
        private final String phase;
        private final SweepResult committed;

        RetentionSweepException(String phase, SweepResult committed, SQLException cause) {
            super("retention sweep failed in phase " + phase + " (committed so far: "
                    + committed.total() + " rows)", cause);
            this.phase = phase;
            this.committed = committed;
        }

        public String phase() {
            return phase;
        }

        /** Counts from the phases that committed before the failure. */
        public SweepResult committed() {
            return committed;
        }
    }

    /**
     * Evicts terminal-case content older than {@code window} as of {@code now}.
     *
     * @throws IllegalArgumentException for a null, zero or negative window
     * @throws RetentionSweepException on a mid-run SQL failure, carrying the failed
     *         phase and the committed-so-far counts
     */
    public static SweepResult sweep(JdbcTemplate jdbc, Duration window, Instant now) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(now, "now");
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("retention window must be positive: " + window);
        }
        String cutoff = now.minus(window).toString();
        return jdbc.execute((ConnectionCallback<SweepResult>) c -> run(c, cutoff));
    }

    private static SweepResult run(Connection c, String cutoff) throws SQLException {
        String phase = "terminal-cases";
        int cases = 0;
        int notes = 0;
        int responses = 0;
        int attachCfg = 0;
        int attachFwd = 0;
        int orphanNotes = 0;
        int orphanResponses = 0;
        int orphanAttachCfg = 0;
        int orphanAttachFwd = 0;
        boolean originalAutoCommit = c.getAutoCommit();
        try {
            try (Statement s = c.createStatement()) {
                // busy_timeout only; journal_mode is persistent per-database and is the
                // store's own concern.
                s.execute("PRAGMA busy_timeout = 5000");
            }
            c.setAutoCommit(false);
            List<String> doomed = doomedTokens(c, cutoff);
            for (int i = 0; i < doomed.size(); i += CHUNK) {
                List<String> chunk = doomed.subList(i, Math.min(i + CHUNK, doomed.size()));
                notes += deleteByTokens(c, "case_note", "case_token", chunk);
                responses += deleteByTokens(c, "case_response", "case_token", chunk);
                attachCfg += deleteByTokens(c, "attachment_config", "case_token", chunk);
                attachFwd += deleteByTokens(c, "attachment_forward_result", "case_token", chunk);
                cases += deleteByTokens(c, "collaboration_request", "token", chunk);
            }
            c.commit();
            phase = "orphans";
            // The age check protects a child cached mid-fetch before its parent lands.
            orphanNotes = orphanDelete(c, "case_note", "fetched_at", cutoff);
            orphanResponses = orphanDelete(c, "case_response", "fetched_at", cutoff);
            orphanAttachCfg = orphanDelete(c, "attachment_config", "fetched_at", cutoff);
            orphanAttachFwd = orphanDelete(c, "attachment_forward_result", "forwarded_at", cutoff);
            c.commit();
            phase = "webhook-events";
            int events;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM webhook_inbound_event WHERE received_at < ?")) {
                ps.setString(1, cutoff);
                events = ps.executeUpdate();
            }
            c.commit();
            return new SweepResult(cases, notes, responses, attachCfg, attachFwd,
                    orphanNotes, orphanResponses, orphanAttachCfg, orphanAttachFwd, events);
        } catch (SQLException e) {
            // Roll back the failing phase EXPLICITLY before the finally restores
            // autocommit: setAutoCommit(true) on a connection with an open
            // transaction COMMITS it, which would silently land a partial phase and
            // falsify the exception's committed-so-far contract (gate on the PR).
            try {
                c.rollback();
            } catch (SQLException rollbackFailed) {
                e.addSuppressed(rollbackFailed);
            }
            boolean terminalCommitted = !"terminal-cases".equals(phase);
            boolean orphansCommitted = "webhook-events".equals(phase);
            throw new RetentionSweepException(phase, new SweepResult(
                    terminalCommitted ? cases : 0,
                    terminalCommitted ? notes : 0,
                    terminalCommitted ? responses : 0,
                    terminalCommitted ? attachCfg : 0,
                    terminalCommitted ? attachFwd : 0,
                    orphansCommitted ? orphanNotes : 0,
                    orphansCommitted ? orphanResponses : 0,
                    orphansCommitted ? orphanAttachCfg : 0,
                    orphansCommitted ? orphanAttachFwd : 0,
                    0), e);
        } finally {
            // Connection hygiene: the template may hand this connection to others.
            try {
                c.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
                // restoring autocommit failed; the connection is being discarded anyway
            }
        }
    }

    /** Terminal set: CLOSED + REJECTED; PENDINGACTION is deliberately not terminal. */
    private static List<String> doomedTokens(Connection c, String cutoff) throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
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

    private static int deleteByTokens(Connection c, String table, String column, List<String> tokens)
            throws SQLException {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + in + ")")) {
            for (int i = 0; i < tokens.size(); i++) {
                ps.setString(i + 1, tokens.get(i));
            }
            return ps.executeUpdate();
        }
    }

    /** NOT IN is NULL-safe here: collaboration_request.token is NOT NULL UNIQUE. */
    private static int orphanDelete(Connection c, String table, String ageColumn, String cutoff)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table + " WHERE " + ageColumn + " < ?"
                        + " AND case_token NOT IN (SELECT token FROM collaboration_request)")) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        }
    }
}
