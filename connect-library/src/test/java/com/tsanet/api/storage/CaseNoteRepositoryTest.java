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

    private static CaseNoteDto note(long id, String token, String type, Long companyId, String direction) {
        return new CaseNoteDto(id, 1L, "tok1", "Acme", "api@acme.com", "api@acme.com", "API", "Summary",
            "Body", "LOW", "ACTIVE", token, null, null, type, companyId, direction);
    }

    @Test
    void typeCompanyAndDirectionRoundTripAndAbsentOnesStayNull() {
        JdbcTemplate jdbcTemplate = database("case-note-round-trip");
        DatabaseInitializer.createSchema(jdbcTemplate);
        CaseNoteRepository repository = new CaseNoteRepository(jdbcTemplate);

        repository.saveAll(List.of(
            note(1L, "n1", "USER_PUBLIC", 1112L, "OUTBOUND"),
            note(2L, "n2", null, null, null)));

        assertThat(repository.findByCaseToken("tok1"))
            .extracting(CaseNoteDto::id, CaseNoteDto::type, CaseNoteDto::companyId, CaseNoteDto::direction)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "USER_PUBLIC", 1112L, "OUTBOUND"),
                org.assertj.core.groups.Tuple.tuple(2L, null, null, null));
    }

    /**
     * The DDL below is a FROZEN copy of case_note as connect-library 2.0.0 created it (from the
     * v2.0.0 tag), on purpose: deriving it from current constants would make this test pass by
     * construction. It proves an upgrade's two claims: createSchema adds the three columns to an
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
        upgraded.saveAll(List.of(note(1L, "n1", "USER_PARTNER", 1113L, "INBOUND")));

        assertThat(upgraded.findAll())
            .extracting(CaseNoteDto::id, CaseNoteDto::type, CaseNoteDto::companyId, CaseNoteDto::direction)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "USER_PARTNER", 1113L, "INBOUND"),
                org.assertj.core.groups.Tuple.tuple(2L, null, null, null));
    }
}
