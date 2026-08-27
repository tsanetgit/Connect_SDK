package com.tsanet.api.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
        CacheRetentionRepository repo = new CacheRetentionRepository(c);
        try {
            repo.setBusyTimeout(5000);
            c.setAutoCommit(false);
            List<String> doomed = repo.findDoomedTokens(cutoff);
            for (int i = 0; i < doomed.size(); i += CHUNK) {
                List<String> chunk = doomed.subList(i, Math.min(i + CHUNK, doomed.size()));
                notes += repo.deleteByTokens("case_note", "case_token", chunk);
                responses += repo.deleteByTokens("case_response", "case_token", chunk);
                attachCfg += repo.deleteByTokens("attachment_config", "case_token", chunk);
                attachFwd += repo.deleteByTokens("attachment_forward_result", "case_token", chunk);
                cases += repo.deleteByTokens("collaboration_request", "token", chunk);
            }
            c.commit();
            phase = "orphans";
            // The age check protects a child cached mid-fetch before its parent lands.
            orphanNotes = repo.deleteOrphans("case_note", "fetched_at", cutoff);
            orphanResponses = repo.deleteOrphans("case_response", "fetched_at", cutoff);
            orphanAttachCfg = repo.deleteOrphans("attachment_config", "fetched_at", cutoff);
            orphanAttachFwd = repo.deleteOrphans("attachment_forward_result", "forwarded_at", cutoff);
            c.commit();
            phase = "webhook-events";
            int events = repo.deleteExpiredWebhookEvents(cutoff);
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
}
