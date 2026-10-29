package com.tsanet.api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class CollaborationRequestRepositoryTest {
    private CollaborationRequestRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-collaboration-request-repo.db");
        jdbcTemplate = new JdbcTemplate(dataSource);
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

    @Test
    void itRoundTripsTheE2eeFieldsThroughTheCacheUnchanged() {
        String manifest = "eyJhbGciOiJFUzI1NiJ9.eyJ2ZXIiOiJ0c2FuZXQtZTJlZS0xIn0.c2lnLV9-";
        repository.saveAll(
            List.of(
                new CollaborationRequestStatusDto(1L, "OPEN", "{\"recipients\":[]}", "Acme", 1L, "Beta", 2L, "tok1",
                    null, null, false, manifest, true),
                new CollaborationRequestStatusDto(2L, "OPEN", "Plain", "Acme", 1L, "Beta", 2L, "tok2",
                    null, null, false, null, false)
            )
        );

        assertThat(repository.findAll())
            .extracting(CollaborationRequestStatusDto::id, CollaborationRequestStatusDto::manifest,
                CollaborationRequestStatusDto::encrypted)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, manifest, Boolean.TRUE),
                org.assertj.core.groups.Tuple.tuple(2L, null, Boolean.FALSE)
            );
        assertThat(repository.findByCompanyId(1L))
            .extracting(CollaborationRequestStatusDto::manifest)
            .containsExactly(manifest, null);
    }

    @Test
    void itRoundTripsTheCaseContentKeepingEmptyAndNullAnswersApart() {
        List<CaseCustomFieldDto> answers = java.util.Arrays.asList(
            new CaseCustomFieldDto(501L, "Product", "{\"recipients\":[]}"),
            new CaseCustomFieldDto(502L, "Optional note", ""),
            new CaseCustomFieldDto(503L, "Unanswered", null));
        repository.saveAll(
            List.of(
                new CollaborationRequestStatusDto(1L, "OPEN", "s", "Acme", 1L, "Beta", 2L, "tok1", null, null,
                    false, "m.a.n", true, "A-2026-004417", " {\"recipients\":[]} ", answers),
                new CollaborationRequestStatusDto(2L, "OPEN", "s", "Acme", 1L, "Beta", 2L, "tok2", null, null,
                    false, null, false, null, null, List.of())
            )
        );

        assertThat(repository.findAll()).first().satisfies(stored -> {
            assertThat(stored.submitterCaseNumber()).isEqualTo("A-2026-004417");
            assertThat(stored.description()).isEqualTo(" {\"recipients\":[]} ");
            assertThat(stored.customFields()).containsExactlyElementsOf(answers);
        });
        assertThat(repository.findByCompanyId(1L)).last().satisfies(stored -> {
            assertThat(stored.submitterCaseNumber()).isNull();
            assertThat(stored.description()).isNull();
            assertThat(stored.customFields()).isEmpty();
        });
    }

    /**
     * A FROZEN copy of the stored JSON shape, on purpose: the round-trip tests write and read with the
     * same record, so a renamed CaseCustomFieldDto component would pass them while every cached row
     * read back as null. This literal is what this version writes; changing it needs a migration.
     */
    @Test
    void itReadsTheStoredCustomFieldsShape() {
        jdbcTemplate.update(
            "INSERT INTO collaboration_request (id, status, summary, submit_company_id, receive_company_id, token,"
                + " fetched_at, custom_fields_json) VALUES (1, 'OPEN', 's', 1, 2, 'tok1', 'x',"
                + " '[{\"id\":9,\"fieldName\":\"F\",\"value\":\"\"},{\"id\":10,\"fieldName\":\"G\",\"value\":null}]')");

        assertThat(repository.findAll()).singleElement()
            .extracting(CollaborationRequestStatusDto::customFields)
            .isEqualTo(java.util.Arrays.asList(new CaseCustomFieldDto(9L, "F", ""), new CaseCustomFieldDto(10L, "G", null)));
    }

    @Test
    void anUnreadableCustomFieldsRowReadsNotKnownAndTheRestStillRead() {
        repository.saveAll(List.of(
            new CollaborationRequestStatusDto(1L, "OPEN", "s", "Acme", 1L, "Beta", 2L, "tok1", null, null,
                false, null, false, "C-1", "d", List.of(new CaseCustomFieldDto(9L, "F", "v"))),
            new CollaborationRequestStatusDto(2L, "OPEN", "s", "Acme", 1L, "Beta", 2L, "tok2", null, null,
                false, null, false, "C-2", "d", List.of(new CaseCustomFieldDto(8L, "G", "w")))));
        jdbcTemplate.update("UPDATE collaboration_request SET custom_fields_json = 'not json' WHERE id = 1");

        assertThat(repository.findAll())
            .extracting(CollaborationRequestStatusDto::id, CollaborationRequestStatusDto::customFields)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, null),
                org.assertj.core.groups.Tuple.tuple(2L, List.of(new CaseCustomFieldDto(8L, "G", "w"))));
    }

    /**
     * The DDL below is a FROZEN copy of collaboration_request as connect-library 3.0.0 created it,
     * on purpose. A refetched row gets the case content; a row that isn't refetched reads all three
     * as null ("not known"), never as "no description" or "no answers".
     */
    @Test
    void itUpgradesA300CacheAndFillsTheCaseContentColumnsOnRefetch() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-collaboration-request-content-upgrade-" + System.nanoTime() + ".db");
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
                test_case INTEGER,
                fetched_at TEXT NOT NULL
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO collaboration_request (id, status, summary, submit_company_name, submit_company_id,"
                + " receive_company_name, receive_company_id, token, created_at, updated_at, test_case, fetched_at)"
                + " VALUES (1, 'OPEN', 'Old row', 'Acme', 1, 'Beta', 2, 'tok1', NULL, NULL, 0, 'x'),"
                + " (2, 'OPEN', 'Untouched old row', 'Acme', 1, 'Beta', 2, 'tok2', NULL, NULL, 0, 'x')"
        );

        DatabaseInitializer.createSchema(jdbcTemplate);
        DatabaseInitializer.createSchema(jdbcTemplate);
        CollaborationRequestRepository upgraded = new CollaborationRequestRepository(jdbcTemplate);
        upgraded.saveAll(List.of(new CollaborationRequestStatusDto(1L, "OPEN", "Old row", "Acme", 1L, "Beta", 2L,
            "tok1", null, null, false, null, false, "C-1", "Body", List.of(new CaseCustomFieldDto(9L, "F", "v")))));

        assertThat(upgraded.findAll())
            .extracting(CollaborationRequestStatusDto::id, CollaborationRequestStatusDto::submitterCaseNumber,
                CollaborationRequestStatusDto::description, CollaborationRequestStatusDto::customFields)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "C-1", "Body", List.of(new CaseCustomFieldDto(9L, "F", "v"))),
                org.assertj.core.groups.Tuple.tuple(2L, null, null, null)
            );
    }

    /**
     * The DDL below is a FROZEN copy of collaboration_request as connect-library 3.0.0 created it
     * (test_case, no manifest or encrypted), on purpose: deriving it from current constants would
     * make this test pass by construction. A refetched row gets both values; a row that isn't
     * refetched reads encrypted as null ("not known"), never false.
     */
    @Test
    void itUpgradesA300CacheAndFillsTheE2eeColumnsOnRefetch() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-collaboration-request-e2ee-upgrade-" + System.nanoTime() + ".db");
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
                test_case INTEGER,
                fetched_at TEXT NOT NULL
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO collaboration_request (id, status, summary, submit_company_name, submit_company_id,"
                + " receive_company_name, receive_company_id, token, created_at, updated_at, test_case, fetched_at)"
                + " VALUES (1, 'OPEN', 'Old row', 'Acme', 1, 'Beta', 2, 'tok1', NULL, NULL, 0, 'x'),"
                + " (2, 'OPEN', 'Untouched old row', 'Acme', 1, 'Beta', 2, 'tok2', NULL, NULL, 0, 'x')"
        );

        DatabaseInitializer.createSchema(jdbcTemplate);
        DatabaseInitializer.createSchema(jdbcTemplate);
        CollaborationRequestRepository upgraded = new CollaborationRequestRepository(jdbcTemplate);
        upgraded.saveAll(List.of(new CollaborationRequestStatusDto(
            1L, "OPEN", "Old row", "Acme", 1L, "Beta", 2L, "tok1", null, null, false, "m.a.n", true)));

        assertThat(upgraded.findAll())
            .extracting(CollaborationRequestStatusDto::id, CollaborationRequestStatusDto::manifest,
                CollaborationRequestStatusDto::encrypted)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "m.a.n", Boolean.TRUE),
                org.assertj.core.groups.Tuple.tuple(2L, null, null)
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
