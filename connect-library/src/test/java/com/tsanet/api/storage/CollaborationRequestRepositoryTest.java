package com.tsanet.api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class CollaborationRequestRepositoryTest {
    private CollaborationRequestRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-collaboration-request-repo.db");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer.createSchema(jdbcTemplate);
        jdbcTemplate.execute("DELETE FROM collaboration_request");
        repository = new CollaborationRequestRepository(jdbcTemplate);
    }

    @Test
    void itStoresAndReadsCollaborationRequests() {
        repository.saveAll(
            List.of(
                new CollaborationRequestStatusDto(
                    1L,
                    "OPEN",
                    "Need help",
                    "Acme",
                    10L,
                    "Beta",
                    20L,
                    "tok1",
                    "2026-01-01T00:00:00Z",
                    "2026-01-02T00:00:00Z"
                )
            )
        );

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findByCompanyId(10L)).extracting(CollaborationRequestStatusDto::id).containsExactly(1L);
        assertThat(repository.findByCompanyId(99L)).isEmpty();
    }

    @Test
    void itUpdatesExistingRequestsOnConflict() {
        repository.saveAll(
            List.of(new CollaborationRequestStatusDto(1L, "OPEN", "Old", "Acme", 1L, "Beta", 2L, "tok1", null, null))
        );
        repository.saveAll(
            List.of(new CollaborationRequestStatusDto(1L, "CLOSED", "New", "Acme", 1L, "Beta", 2L, "tok1", null, null))
        );

        assertThat(repository.findAll())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::status, CollaborationRequestStatusDto::summary)
            .containsExactly("CLOSED", "New");
    }

    @Test
    void itRoundTripsTestCaseThroughTheCache() {
        repository.saveAll(
            List.of(
                new CollaborationRequestStatusDto(1L, "OPEN", "Real", "Acme", 1L, "Beta", 2L, "tok1", null, null, false),
                new CollaborationRequestStatusDto(2L, "OPEN", "Test", "Acme", 1L, "Beta", 2L, "tok2", null, null, true)
            )
        );

        assertThat(repository.findAll())
            .extracting(CollaborationRequestStatusDto::id, CollaborationRequestStatusDto::testCase)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, Boolean.FALSE),
                org.assertj.core.groups.Tuple.tuple(2L, Boolean.TRUE)
            );
    }

    /**
     * The DDL below is a FROZEN copy of collaboration_request as it existed before the
     * test_case column, on purpose: deriving it from current constants would make this
     * test pass by construction. It proves the two claims an upgrade rests on:
     * createSchema adds the column to a pre-existing database (CREATE TABLE IF NOT
     * EXISTS alone is a no-op there), and the upsert's UPDATE arm populates test_case
     * on a refreshed pre-upgrade row rather than leaving it null forever.
     */
    @Test
    void itUpgradesAPreExistingDatabaseAndBackfillsTestCaseOnRefresh() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-collaboration-request-upgrade-" + System.nanoTime() + ".db");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS collaboration_request (
                id INTEGER PRIMARY KEY,
                status TEXT,
                summary TEXT,
                submit_company_name TEXT,
                submit_company_id INTEGER,
                receive_company_name TEXT,
                receive_company_id INTEGER,
                token TEXT NOT NULL UNIQUE,
                created_at TEXT,
                updated_at TEXT,
                fetched_at TEXT NOT NULL
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO collaboration_request (id, status, summary, submit_company_name, submit_company_id,"
                + " receive_company_name, receive_company_id, token, created_at, updated_at, fetched_at)"
                + " VALUES (1, 'OPEN', 'Old row', 'Acme', 1, 'Beta', 2, 'tok1', NULL, NULL, 'x'),"
                + " (2, 'OPEN', 'Untouched old row', 'Acme', 1, 'Beta', 2, 'tok2', NULL, NULL, 'x')"
        );

        DatabaseInitializer.createSchema(jdbcTemplate);
        CollaborationRequestRepository upgraded = new CollaborationRequestRepository(jdbcTemplate);

        upgraded.saveAll(
            List.of(new CollaborationRequestStatusDto(1L, "OPEN", "Old row", "Acme", 1L, "Beta", 2L, "tok1", null, null, true))
        );

        assertThat(upgraded.findAll())
            .extracting(CollaborationRequestStatusDto::id, CollaborationRequestStatusDto::testCase)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, Boolean.TRUE),
                org.assertj.core.groups.Tuple.tuple(2L, null)
            );
    }
}
