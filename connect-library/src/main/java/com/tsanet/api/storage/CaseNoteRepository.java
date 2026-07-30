package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class CaseNoteRepository {
    private static final RowMapper<CaseNoteDto> ROW_MAPPER = CaseNoteRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public CaseNoteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<CaseNoteDto> notes) {
        if (notes.isEmpty()) {
            return;
        }

        String fetchedAt = Instant.now().toString();
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO case_note (
                id, case_id, case_token, company_name, creator_username, creator_email,
                creator_name, summary, description, priority, status, token, created_at, updated_at, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                case_id = excluded.case_id,
                case_token = excluded.case_token,
                company_name = excluded.company_name,
                creator_username = excluded.creator_username,
                creator_email = excluded.creator_email,
                creator_name = excluded.creator_name,
                summary = excluded.summary,
                description = excluded.description,
                priority = excluded.priority,
                status = excluded.status,
                token = excluded.token,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                fetched_at = excluded.fetched_at
            """,
            notes,
            notes.size(),
            (ps, note) -> {
                ps.setObject(1, note.id());
                ps.setObject(2, note.caseId());
                ps.setString(3, note.caseToken());
                ps.setString(4, note.companyName());
                ps.setString(5, note.creatorUsername());
                ps.setString(6, note.creatorEmail());
                ps.setString(7, note.creatorName());
                ps.setString(8, note.summary());
                ps.setString(9, note.description());
                ps.setString(10, note.priority());
                ps.setString(11, note.status());
                ps.setString(12, note.token());
                ps.setString(13, note.createdAt());
                ps.setString(14, note.updatedAt());
                ps.setString(15, fetchedAt);
            }
        );
    }

    public List<CaseNoteDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT id, case_id, case_token, company_name, creator_username, creator_email,
                   creator_name, summary, description, priority, status, token, created_at, updated_at
            FROM case_note
            ORDER BY created_at, id
            """,
            ROW_MAPPER
        );
    }

    public List<CaseNoteDto> findByCaseToken(String caseToken) {
        return jdbcTemplate.query(
            """
            SELECT id, case_id, case_token, company_name, creator_username, creator_email,
                   creator_name, summary, description, priority, status, token, created_at, updated_at
            FROM case_note
            WHERE case_token = ?
            ORDER BY created_at, id
            """,
            ROW_MAPPER,
            caseToken
        );
    }

    private static CaseNoteDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CaseNoteDto(
            rs.getObject("id", Long.class),
            rs.getObject("case_id", Long.class),
            rs.getString("case_token"),
            rs.getString("company_name"),
            rs.getString("creator_username"),
            rs.getString("creator_email"),
            rs.getString("creator_name"),
            rs.getString("summary"),
            rs.getString("description"),
            rs.getString("priority"),
            rs.getString("status"),
            rs.getString("token"),
            rs.getString("created_at"),
            rs.getString("updated_at")
        );
    }
}
