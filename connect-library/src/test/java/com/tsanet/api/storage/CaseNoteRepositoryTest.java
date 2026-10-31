package com.tsanet.api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class CaseNoteRepositoryTest {

    private static JdbcTemplate database(String name) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-" + name + "-" + System.nanoTime() + ".db");
        return new JdbcTemplate(dataSource);
    }

    private static CaseNoteDto note(long id, String token, String type, String typeDisplayName, Long companyId,
                                    String direction) {
        return new CaseNoteDto(id, 1L, "tok1", "Acme", "api@acme.com", "api@acme.com", "API", "Summary",
            "Body", "LOW", "ACTIVE", token, null, null, type, companyId, direction, typeDisplayName, null, null);
    }

    @Test
    void typeLabelCompanyAndDirectionRoundTripAndAbsentOnesStayNull() {
        JdbcTemplate jdbcTemplate = database("case-note-round-trip");
        DatabaseInitializer.createSchema(jdbcTemplate);
        CaseNoteRepository repository = new CaseNoteRepository(jdbcTemplate);

        repository.saveAll(List.of(
            note(1L, "n1", "public", "Public", 101L, "OUTBOUND"),
            note(2L, "n2", null, null, null, null)));

        assertThat(repository.findByCaseToken("tok1"))
            .extracting(CaseNoteDto::id, CaseNoteDto::type, CaseNoteDto::typeDisplayName, CaseNoteDto::companyId,
                CaseNoteDto::direction)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "public", "Public", 101L, "OUTBOUND"),
                org.assertj.core.groups.Tuple.tuple(2L, null, null, null, null));
    }

    private static CaseNoteDto e2eeNote(long id, String token, String manifest, Boolean encrypted) {
        return new CaseNoteDto(id, 1L, "tok1", "Acme", "api@acme.com", "api@acme.com", "API", "Summary",
            "Body", "LOW", "ACTIVE", token, null, null, null, null, null, null, manifest, encrypted);
    }

    @Test
    void theE2eeFieldsRoundTripUnchanged() {
        JdbcTemplate jdbcTemplate = database("case-note-e2ee-round-trip");
        DatabaseInitializer.createSchema(jdbcTemplate);
        CaseNoteRepository repository = new CaseNoteRepository(jdbcTemplate);
        String manifest = "eyJhbGciOiJFUzI1NiJ9.eyJ2ZXIiOiJ0c2FuZXQtZTJlZS0xIn0.c2lnLV9-";

        repository.saveAll(List.of(e2eeNote(1L, "n1", manifest, true), e2eeNote(2L, "n2", null, false)));

        assertThat(repository.findByCaseToken("tok1"))
            .extracting(CaseNoteDto::id, CaseNoteDto::manifest, CaseNoteDto::encrypted)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, manifest, Boolean.TRUE),
                org.assertj.core.groups.Tuple.tuple(2L, null, Boolean.FALSE));
        assertThat(repository.findAll()).extracting(CaseNoteDto::manifest).containsExactly(manifest, null);
    }

    /**
     * The DDL below is a FROZEN copy of case_note as connect-library 3.0.0 created it (type, company_id,
     * direction and type_display_name, no manifest or encrypted), on purpose: deriving it from current
     * constants would make this test pass by construction. A refetched note gets both values; one that
     * isn't refetched reads encrypted as null ("not known"), never false.
     */
    @Test
    void itUpgradesA300CacheAndFillsTheE2eeColumnsOnRefetch() {
        JdbcTemplate jdbcTemplate = database("case-note-e2ee-upgrade");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS case_note (
                id INTEGER PRIMARY KEY,
                case_id INTEGER,
                case_token TEXT NOT NULL,
                company_name TEXT,
                creator_username TEXT,
                creator_email TEXT,
                creator_name TEXT,
                summary TEXT,
                description TEXT,
                priority TEXT,
                status TEXT,
                token TEXT NOT NULL UNIQUE,
                created_at TEXT,
                updated_at TEXT,
                fetched_at TEXT NOT NULL,
                type TEXT,
                company_id INTEGER,
                direction TEXT,
                type_display_name TEXT
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO case_note (id, case_id, case_token, company_name, creator_username, creator_email,"
                + " creator_name, summary, description, priority, status, token, created_at, updated_at, fetched_at)"
                + " VALUES (1, 1, 'tok1', 'Acme', 'u', 'e', 'n', 'Old note', 'b', 'LOW', 'ACTIVE', 'n1', NULL, NULL, 'x'),"
                + " (2, 1, 'tok1', 'Acme', 'u', 'e', 'n', 'Untouched old note', 'b', 'LOW', 'ACTIVE', 'n2', NULL, NULL, 'x')"
        );

        DatabaseInitializer.createSchema(jdbcTemplate);
        DatabaseInitializer.createSchema(jdbcTemplate);
        CaseNoteRepository upgraded = new CaseNoteRepository(jdbcTemplate);
        upgraded.saveAll(List.of(e2eeNote(1L, "n1", "m.a.n", true)));

        assertThat(upgraded.findAll())
            .extracting(CaseNoteDto::id, CaseNoteDto::manifest, CaseNoteDto::encrypted)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "m.a.n", Boolean.TRUE),
                org.assertj.core.groups.Tuple.tuple(2L, null, null));
    }

    /**
     * The DDL below is a FROZEN copy of case_note as connect-library 2.0.0 created it (from the
     * v2.0.0 tag), on purpose: deriving it from current constants would make this test pass by
     * construction. It proves an upgrade's two claims: createSchema adds the four columns to an
     * existing cache (CREATE TABLE IF NOT EXISTS alone is a no-op there), and a refetched note
     * gets its values while one that isn't refetched keeps nulls.
     */
    @Test
    void itUpgradesA200CacheAndFillsTheNewColumnsOnRefetch() {
        JdbcTemplate jdbcTemplate = database("case-note-upgrade");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS case_note (
                id INTEGER PRIMARY KEY,
                case_id INTEGER,
                case_token TEXT NOT NULL,
                company_name TEXT,
                creator_username TEXT,
                creator_email TEXT,
                creator_name TEXT,
                summary TEXT,
                description TEXT,
                priority TEXT,
                status TEXT,
                token TEXT NOT NULL UNIQUE,
                created_at TEXT,
                updated_at TEXT,
                fetched_at TEXT NOT NULL
            )
            """);
        jdbcTemplate.update(
            "INSERT INTO case_note (id, case_id, case_token, company_name, creator_username, creator_email,"
                + " creator_name, summary, description, priority, status, token, created_at, updated_at, fetched_at)"
                + " VALUES (1, 1, 'tok1', 'Acme', 'u', 'e', 'n', 'Old note', 'b', 'LOW', 'ACTIVE', 'n1', NULL, NULL, 'x'),"
                + " (2, 1, 'tok1', 'Acme', 'u', 'e', 'n', 'Untouched old note', 'b', 'LOW', 'ACTIVE', 'n2', NULL, NULL, 'x')"
        );

        DatabaseInitializer.createSchema(jdbcTemplate);
        DatabaseInitializer.createSchema(jdbcTemplate);
        CaseNoteRepository upgraded = new CaseNoteRepository(jdbcTemplate);
        upgraded.saveAll(List.of(note(1L, "n1", "partner", "Partner", 202L, "INBOUND")));

        assertThat(upgraded.findAll())
            .extracting(CaseNoteDto::id, CaseNoteDto::type, CaseNoteDto::typeDisplayName, CaseNoteDto::companyId,
                CaseNoteDto::direction)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "partner", "Partner", 202L, "INBOUND"),
                org.assertj.core.groups.Tuple.tuple(2L, null, null, null, null));
    }
}
