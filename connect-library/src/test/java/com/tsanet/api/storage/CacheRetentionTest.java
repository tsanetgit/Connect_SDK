package com.tsanet.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * The schema comes from {@link DatabaseInitializer#createSchema} itself — the point
 * of hosting the sweep in-library is that its SQL and the schema live and move
 * together (Connect_SDK#65). Test matrix ported from the adapter's interim sweep
 * (tsanetgit/Fin-Intercom_App#69), where every case was red/green-probed.
 */
class CacheRetentionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final String OLD = NOW.minus(Duration.ofDays(45)).toString();
    private static final String FRESH = NOW.minus(Duration.ofDays(2)).toString();

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-cache-retention.db");
        jdbc = new JdbcTemplate(dataSource);
        DatabaseInitializer.createSchema(jdbc);
        // Drop-and-recreate only the swept tables: the file persists across runs and
        // reference tables may carry rows; createSchema is IF NOT EXISTS, so the
        // second call rebuilds exactly the dropped ones.
        for (String t : List.of("collaboration_request", "case_note", "case_response",
                "attachment_config", "attachment_forward_result", "webhook_inbound_event")) {
            jdbc.execute("DROP TABLE IF EXISTS " + t);
        }
        DatabaseInitializer.createSchema(jdbc);
    }

    private void seedCase(long id, String token, String status, String updatedAt, String fetchedAt) {
        jdbc.update("INSERT INTO collaboration_request (id, status, summary, token, updated_at, fetched_at)"
                + " VALUES (?,?,?,?,?,?)", id, status, "case " + token, token, updatedAt, fetchedAt);
    }

    private void seedChildren(String token, String age) {
        jdbc.update("INSERT INTO case_note (id, case_token, summary, description, token, fetched_at)"
                + " VALUES ((SELECT COALESCE(MAX(id),0)+1 FROM case_note),?,?,?,?,?)",
                token, "note", "text", "note-" + token, age);
        jdbc.update("INSERT INTO case_response (id, case_token, next_steps, fetched_at) VALUES (1,?,?,?)",
                token, "steps", age);
        jdbc.update("INSERT INTO attachment_config (case_token, submitter_company_id, fetched_at)"
                + " VALUES (?,?,?)", token, 1, age);
        jdbc.update("INSERT INTO attachment_forward_result (case_token, description, file_name, forwarded_at)"
                + " VALUES (?,?,?,?)", token, "file", "log.txt", age);
    }

    private List<String> tokens(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    @Test
    void terminalOldEvictsWithChildren_freshAndOpenSurvive() {
        seedCase(1, "gone-closed", "CLOSED", OLD, OLD);
        seedChildren("gone-closed", OLD);
        seedCase(2, "gone-rejected", "REJECTED", null, OLD); // updated_at NULL: fetched_at decides
        seedCase(3, "stay-fresh", "CLOSED", FRESH, FRESH);
        seedChildren("stay-fresh", FRESH);
        seedCase(4, "stay-open", "OPEN", OLD, OLD); // OPEN never ages out, however old
        seedChildren("stay-open", OLD);

        CacheRetention.SweepResult r = CacheRetention.sweep(jdbc, WINDOW, NOW);

        assertThat(r.cases()).isEqualTo(2);
        assertThat(r.notes()).isEqualTo(1);
        assertThat(r.responses()).isEqualTo(1);
        assertThat(r.attachConfigs()).isEqualTo(1);
        assertThat(r.attachForwards()).isEqualTo(1);
        assertThat(tokens("SELECT token FROM collaboration_request ORDER BY token"))
                .containsExactly("stay-fresh", "stay-open");
        assertThat(tokens("SELECT case_token FROM case_note ORDER BY case_token"))
                .as("children of survivors are untouched")
                .containsExactly("stay-fresh", "stay-open");
    }

    @Test
    void coalesceOrderIsPinned_updatedAtWinsOverOldFetchedAt() {
        seedCase(1, "recently-updated", "CLOSED", FRESH, OLD);
        CacheRetention.SweepResult r = CacheRetention.sweep(jdbc, WINDOW, NOW);
        assertThat(r.cases()).as("COALESCE(updated_at, fetched_at): fresh updated_at wins").isZero();
        assertThat(tokens("SELECT token FROM collaboration_request")).containsExactly("recently-updated");
    }

    @Test
    void orphanChildrenAgeOut_freshOrphansSurvive() {
        jdbc.update("INSERT INTO case_note (id, case_token, summary, description, token, fetched_at)"
                + " VALUES (1,?,?,?,?,?)", "no-parent", "note", "text", "orphan-old", OLD);
        jdbc.update("INSERT INTO case_note (id, case_token, summary, description, token, fetched_at)"
                + " VALUES (2,?,?,?,?,?)", "no-parent-2", "note", "text", "orphan-fresh", FRESH);
        CacheRetention.SweepResult r = CacheRetention.sweep(jdbc, WINDOW, NOW);
        assertThat(r.orphanNotes()).isEqualTo(1);
        assertThat(tokens("SELECT token FROM case_note"))
                .as("the fresh orphan survives (mid-fetch protection)")
                .containsExactly("orphan-fresh");
    }

    @Test
    void webhookEventsAgeOutByReceivedAt() {
        jdbc.update("INSERT INTO webhook_inbound_event (event_type, request_token, received_at,"
                + " signature_valid, cache_synced, raw_payload) VALUES (?,?,?,1,1,?)",
                "created", "t1", OLD, "{}");
        jdbc.update("INSERT INTO webhook_inbound_event (event_type, request_token, received_at,"
                + " signature_valid, cache_synced, raw_payload) VALUES (?,?,?,1,1,?)",
                "created", "t2", FRESH, "{}");
        CacheRetention.SweepResult r = CacheRetention.sweep(jdbc, WINDOW, NOW);
        assertThat(r.events()).isEqualTo(1);
        assertThat(tokens("SELECT request_token FROM webhook_inbound_event")).containsExactly("t2");
    }

    @Test
    void partialFailureKeepsCommittedPhases_andTheExceptionSaysSo() {
        seedCase(1, "gone-closed", "CLOSED", OLD, OLD);
        seedChildren("gone-closed", OLD);
        jdbc.execute("DROP TABLE webhook_inbound_event"); // phase 3 fails by construction

        assertThatThrownBy(() -> CacheRetention.sweep(jdbc, WINDOW, NOW))
                .isInstanceOf(CacheRetention.RetentionSweepException.class)
                .satisfies(e -> {
                    CacheRetention.RetentionSweepException rse = (CacheRetention.RetentionSweepException) e;
                    assertThat(rse.phase()).isEqualTo("webhook-events");
                    assertThat(rse.committed().cases())
                            .as("phase 1 committed before the phase-3 failure").isEqualTo(1);
                });
        assertThat(tokens("SELECT token FROM collaboration_request"))
                .as("committed deletes survive the later failure").isEmpty();
    }

    @Test
    void midPhaseFailureRollsBackThatPhaseEntirely() {
        // Fail PARTWAY through phase 1, after the case_note/case_response deletes ran
        // but before the phase commits: attachment_config is missing, so the phase
        // throws mid-flight. The explicit rollback must undo the phase's earlier
        // deletes — without it, restoring autocommit would silently COMMIT them
        // (JDBC: setAutoCommit(true) commits an open transaction).
        seedCase(1, "gone-closed", "CLOSED", OLD, OLD);
        jdbc.update("INSERT INTO case_note (id, case_token, summary, description, token, fetched_at)"
                + " VALUES (1,?,?,?,?,?)", "gone-closed", "note", "text", "note-1", OLD);
        jdbc.execute("DROP TABLE attachment_config");

        assertThatThrownBy(() -> CacheRetention.sweep(jdbc, WINDOW, NOW))
                .isInstanceOf(CacheRetention.RetentionSweepException.class)
                .satisfies(e -> {
                    CacheRetention.RetentionSweepException rse = (CacheRetention.RetentionSweepException) e;
                    assertThat(rse.phase()).isEqualTo("terminal-cases");
                    assertThat(rse.committed().total()).as("nothing committed").isZero();
                });
        assertThat(tokens("SELECT token FROM case_note"))
                .as("the phase's earlier deletes rolled back with it").containsExactly("note-1");
        assertThat(tokens("SELECT token FROM collaboration_request")).containsExactly("gone-closed");
    }

    @Test
    void nonSqlFailureMidPhaseStillRollsBackAndCarriesPhaseAndCommittedCounts() throws SQLException {
        // Round-3 QA on #66: the catch clause caught only SQLException, so a
        // RuntimeException from anywhere in the try skipped the explicit rollback,
        // fell to the finally, and setAutoCommit(true) committed the partial phase —
        // the exact hazard the SQLException path already guards against, just on the
        // other exception type. Reproduces the reviewer's probe: fail between the
        // case_note and case_response deletes in phase 1.
        seedCase(1, "gone-closed", "CLOSED", OLD, OLD);
        jdbc.update("INSERT INTO case_note (id, case_token, summary, description, token, fetched_at)"
                + " VALUES (1,?,?,?,?,?)", "gone-closed", "note", "text", "note-1", OLD);

        SQLiteDataSource realDataSource = new SQLiteDataSource();
        realDataSource.setUrl("jdbc:sqlite:file:target/test-cache-retention.db");
        Connection real = realDataSource.getConnection();
        try {
            // prepareStatement call #1 is the doomed-token SELECT, #2 the case_note
            // DELETE, #3 the case_response DELETE — throw on #3, after #2 has run.
            Connection poisoned = throwingOnNthPrepareStatement(real, 3, new IllegalStateException("boom"));
            JdbcTemplate poisonedJdbc = new JdbcTemplate(singleConnectionDataSource(poisoned));

            assertThatThrownBy(() -> CacheRetention.sweep(poisonedJdbc, WINDOW, NOW))
                    .isInstanceOf(CacheRetention.RetentionSweepException.class)
                    .satisfies(e -> {
                        CacheRetention.RetentionSweepException rse = (CacheRetention.RetentionSweepException) e;
                        assertThat(rse.phase()).isEqualTo("terminal-cases");
                        assertThat(rse.getCause()).isInstanceOf(IllegalStateException.class);
                        assertThat(rse.committed().total()).as("nothing committed").isZero();
                    });
        } finally {
            real.close();
        }
        assertThat(tokens("SELECT token FROM case_note"))
                .as("the phase's earlier delete rolled back with it").containsExactly("note-1");
        assertThat(tokens("SELECT token FROM collaboration_request")).containsExactly("gone-closed");
    }

    private static Connection throwingOnNthPrepareStatement(Connection real, int failOnCall, RuntimeException toThrow) {
        int[] calls = {0};
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && ++calls[0] == failOnCall) {
                        throw toThrow;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static DataSource singleConnectionDataSource(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
                        return connection;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void invalidWindowsAreRejected() {
        assertThatThrownBy(() -> CacheRetention.sweep(jdbc, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CacheRetention.sweep(jdbc, Duration.ZERO, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CacheRetention.sweep(jdbc, Duration.ofDays(-1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
