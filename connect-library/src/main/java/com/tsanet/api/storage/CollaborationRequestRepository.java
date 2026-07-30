package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class CollaborationRequestRepository {
    private static final RowMapper<CollaborationRequestStatusDto> ROW_MAPPER = CollaborationRequestRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public CollaborationRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<CollaborationRequestStatusDto> requests) {
        if (requests.isEmpty()) {
            return;
        }

        String fetchedAt = Instant.now().toString();
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO collaboration_request (
                id, status, summary, submit_company_name, submit_company_id,
                receive_company_name, receive_company_id, token, created_at, updated_at, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                summary = excluded.summary,
                submit_company_name = excluded.submit_company_name,
                submit_company_id = excluded.submit_company_id,
                receive_company_name = excluded.receive_company_name,
                receive_company_id = excluded.receive_company_id,
                token = excluded.token,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                fetched_at = excluded.fetched_at
            """,
            requests,
            requests.size(),
            (ps, request) -> {
                ps.setObject(1, request.id());
                ps.setString(2, request.status());
                ps.setString(3, request.summary());
                ps.setString(4, request.submitCompanyName());
                ps.setObject(5, request.submitCompanyId());
                ps.setString(6, request.receiveCompanyName());
                ps.setObject(7, request.receiveCompanyId());
                ps.setString(8, request.token());
                ps.setString(9, request.createdAt());
                ps.setString(10, request.updatedAt());
                ps.setString(11, fetchedAt);
            }
        );
    }

    public List<CollaborationRequestStatusDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT id, status, summary, submit_company_name, submit_company_id,
                   receive_company_name, receive_company_id, token, created_at, updated_at
            FROM collaboration_request
            ORDER BY id
            """,
            ROW_MAPPER
        );
    }

    public List<CollaborationRequestStatusDto> findByCompanyId(long companyId) {
        return jdbcTemplate.query(
            """
            SELECT id, status, summary, submit_company_name, submit_company_id,
                   receive_company_name, receive_company_id, token, created_at, updated_at
            FROM collaboration_request
            WHERE submit_company_id = ? OR receive_company_id = ?
            ORDER BY id
            """,
            ROW_MAPPER,
            companyId,
            companyId
        );
    }

    private static CollaborationRequestStatusDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CollaborationRequestStatusDto(
            rs.getObject("id", Long.class),
            rs.getString("status"),
            rs.getString("summary"),
            rs.getString("submit_company_name"),
            rs.getObject("submit_company_id", Long.class),
            rs.getString("receive_company_name"),
            rs.getObject("receive_company_id", Long.class),
            rs.getString("token"),
            rs.getString("created_at"),
            rs.getString("updated_at")
        );
    }
}
