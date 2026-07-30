package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class CaseResponseRepository {
    private static final RowMapper<CaseResponseDto> ROW_MAPPER = CaseResponseRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public CaseResponseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<CaseResponseDto> responses) {
        if (responses.isEmpty()) {
            return;
        }

        String fetchedAt = Instant.now().toString();
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO case_response (
                id, case_token, type, case_number, engineer_name, engineer_phone,
                engineer_email, next_steps, created_at, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id, case_token) DO UPDATE SET
                type = excluded.type,
                case_number = excluded.case_number,
                engineer_name = excluded.engineer_name,
                engineer_phone = excluded.engineer_phone,
                engineer_email = excluded.engineer_email,
                next_steps = excluded.next_steps,
                created_at = excluded.created_at,
                fetched_at = excluded.fetched_at
            """,
            responses,
            responses.size(),
            (ps, response) -> {
                ps.setObject(1, response.id());
                ps.setString(2, response.caseToken());
                ps.setString(3, response.type());
                ps.setString(4, response.caseNumber());
                ps.setString(5, response.engineerName());
                ps.setString(6, response.engineerPhone());
                ps.setString(7, response.engineerEmail());
                ps.setString(8, response.nextSteps());
                ps.setString(9, response.createdAt());
                ps.setString(10, fetchedAt);
            }
        );
    }

    public List<CaseResponseDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT id, case_token, type, case_number, engineer_name, engineer_phone,
                   engineer_email, next_steps, created_at
            FROM case_response
            ORDER BY case_token, id
            """,
            ROW_MAPPER
        );
    }

    public List<CaseResponseDto> findByCaseToken(String caseToken) {
        return jdbcTemplate.query(
            """
            SELECT id, case_token, type, case_number, engineer_name, engineer_phone,
                   engineer_email, next_steps, created_at
            FROM case_response
            WHERE case_token = ?
            ORDER BY id
            """,
            ROW_MAPPER,
            caseToken
        );
    }

    private static CaseResponseDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CaseResponseDto(
            rs.getObject("id", Long.class),
            rs.getString("case_token"),
            rs.getString("type"),
            rs.getString("case_number"),
            rs.getString("engineer_name"),
            rs.getString("engineer_phone"),
            rs.getString("engineer_email"),
            rs.getString("next_steps"),
            rs.getString("created_at")
        );
    }
}
