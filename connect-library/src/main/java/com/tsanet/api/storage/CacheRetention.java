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
 * raw webhook payloads — and rows otherwise persist until the file is deleted.
 * Re-fetchability is the criterion for what this class touches, not a blanket
 * property of everything cached here: eligible rows can be re-fetched from the
 * Connect API by token, so losing them costs a round trip, not the data.
 *
 * <p>Semantics (ported verbatim from the Fin/Intercom adapter's interim sweep, where
 * they were probed and red/green-tested — tsanetgit/Fin-Intercom_App#69 — then
 * narrowed by round-3 QA on Connect_SDK#66):
 *
 * <ul>
 *   <li><b>Terminal cases only</b> ({@code CLOSED}/{@code REJECTED}) older than the
 *       window, age judged by {@code COALESCE(updated_at, fetched_at)}; {@code OPEN}
 *       cases never age out — unresolved is live state, not residue.</li>
 *   <li>Two phases, each committed in its own transaction: phase 1 deletes the
 *       doomed token set with its {@code case_note} / {@code case_response} /
 *       {@code attachment_config} children, children before parents, chunked
 *       IN-lists, all in one transaction. Phase 2, separately: {@code
 *       webhook_inbound_event} by {@code received_at} — a delivery log, not case
 *       content, aged independently of case status.</li>
 *   <li>{@code attachment_forward_result} is never touched by this class:
 *       {@code POST /v1/collaboration-requests/{token}/attachments} has no GET
 *       counterpart, so it is a local record of an outbound action, not a cache of
 *       remote state — eviction here would be unrecoverable. It carries no foreign
 *       key to {@code collaboration_request} (see {@link DatabaseInitializer}), so a
 *       doomed case's forward-result rows deliberately outlive it: phase 1 deletes
 *       the case and its other children but leaves these behind, permanently
 *       orphaned by {@code case_token}. Nothing reaps them; that is this class
 *       choosing not to, not an oversight.</li>
 *   <li>Children with no cached parent are never swept, at any age: three fetch
 *       paths ({@code getNotes}, {@code getResponses}, {@code getAttachmentConfig})
 *       cache {@code case_note}/{@code case_response}/{@code attachment_config}
 *       without ever writing the parent {@code collaboration_request} row, so for a
 *       webhook-driven consumer a parentless-but-live child is the steady state, not
 *       a transient race. An earlier version of this class aged these out by
 *       {@code fetched_at} regardless of the case's actual status on the server;
 *       round-3 QA on #66 traced the three fetch paths and found that assumption
 *       false. The tradeoff accepted instead: rows for cases this library never
 *       learns the fate of accumulate until the parent is eventually cached and ages
 *       out normally, or forever if it never is.</li>
 *   <li>Reference/config tables ({@code user_context}, {@code partner_selection},
 *       {@code collaboration_request_form}, {@code webhook_subscription}) untouched.</li>
 *   <li>{@code fetched_at} and {@code received_at} are {@code Instant.toString()}
 *       (see the repositories) — UTC, so a lexicographic compare against a
 *       same-format cutoff is correct to within fraction-length noise at the
 *       boundary. {@code updated_at}, the first {@code COALESCE} term phase 1 ages
 *       by, is written as {@code OffsetDateTime.toString()} straight from the API
 *       response instead and can carry a non-UTC offset, so its lexicographic
 *       compare against the cutoff isn't the same tight guarantee — bounded skew,
 *       not exact, and not a concern at retention-window scale, but a real gap
 *       versus the other two columns (QA round 3 on #66).</li>
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
    public record SweepResult(int cases, int notes, int responses, int attachConfigs, int events) {
        public int total() {
            return cases + notes + responses + attachConfigs + events;
        }
    }

    /** A mid-run failure: {@code phase} failed, {@code committed} is what already landed. */
    public static final class RetentionSweepException extends RuntimeException {
        private final String phase;
        private final SweepResult committed;

        RetentionSweepException(String phase, SweepResult committed, Exception cause) {
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
     * @throws RetentionSweepException on a mid-run failure, carrying the failed phase
     *         and the committed-so-far counts
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
                cases += repo.deleteByTokens("collaboration_request", "token", chunk);
            }
            c.commit();
            phase = "webhook-events";
            int events = repo.deleteExpiredWebhookEvents(cutoff);
            c.commit();
            return new SweepResult(cases, notes, responses, attachCfg, events);
        } catch (Exception e) {
            // Roll back the failing phase EXPLICITLY before the finally restores
            // autocommit: setAutoCommit(true) on a connection with an open
            // transaction COMMITS it, which would silently land a partial phase and
            // falsify the exception's committed-so-far contract (gate on the PR).
            // Catches Exception, not just SQLException: a RuntimeException from
            // anywhere in the try block hits this same finally-autocommit hazard, and
            // the caller needs the phase + committed-so-far contract regardless of
            // what kind of failure it was (round-3 QA on #66).
            try {
                c.rollback();
            } catch (SQLException rollbackFailed) {
                e.addSuppressed(rollbackFailed);
            }
            boolean terminalCommitted = !"terminal-cases".equals(phase);
            throw new RetentionSweepException(phase, new SweepResult(
                    terminalCommitted ? cases : 0,
                    terminalCommitted ? notes : 0,
                    terminalCommitted ? responses : 0,
                    terminalCommitted ? attachCfg : 0,
                    0), e);
        } catch (Error e) {
            // Same partial-commit hazard as above, but an Error isn't wrapped: don't
            // allocate app-level exception state while the JVM may be in trouble, and
            // Error isn't part of this method's own failure contract.
            try {
                c.rollback();
            } catch (SQLException rollbackFailed) {
                e.addSuppressed(rollbackFailed);
            }
            throw e;
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
