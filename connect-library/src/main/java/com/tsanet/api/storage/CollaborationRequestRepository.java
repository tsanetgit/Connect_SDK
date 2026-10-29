package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class CollaborationRequestRepository {
    private static final RowMapper<CollaborationRequestStatusDto> ROW_MAPPER = CollaborationRequestRepository::mapRow;
    // Custom-field answers are stored as one JSON array, which keeps an empty-string answer
    // and a null one apart and preserves the API's order.
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<CaseCustomFieldDto>> CUSTOM_FIELDS_TYPE = new TypeReference<>() {};

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
                receive_company_name, receive_company_id, token, created_at, updated_at,
                test_case, fetched_at, manifest, encrypted, submitter_case_number, description,
                custom_fields_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                test_case = excluded.test_case,
                fetched_at = excluded.fetched_at,
                manifest = excluded.manifest,
                encrypted = excluded.encrypted,
                submitter_case_number = excluded.submitter_case_number,
                description = excluded.description,
                custom_fields_json = excluded.custom_fields_json
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
                ps.setObject(11, toStoredBoolean(request.testCase()));
                ps.setString(12, fetchedAt);
                ps.setString(13, request.manifest());
                ps.setObject(14, toStoredBoolean(request.encrypted()));
                ps.setString(15, request.submitterCaseNumber());
                ps.setString(16, request.description());
                ps.setString(17, toStoredCustomFields(request.customFields()));
            }
        );
    }

    public List<CollaborationRequestStatusDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT id, status, summary, submit_company_name, submit_company_id,
                   receive_company_name, receive_company_id, token, created_at, updated_at,
                   test_case, manifest, encrypted, submitter_case_number, description, custom_fields_json
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
                   receive_company_name, receive_company_id, token, created_at, updated_at,
                   test_case, manifest, encrypted, submitter_case_number, description, custom_fields_json
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
            rs.getString("updated_at"),
            readStoredBoolean(rs, "test_case"),
            rs.getString("manifest"),
            readStoredBoolean(rs, "encrypted"),
            rs.getString("submitter_case_number"),
            rs.getString("description"),
            readStoredCustomFields(rs.getString("custom_fields_json"))
        );
    }

    private static Integer toStoredBoolean(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }

    // The SQLite driver's typed getObject(col, Integer.class) throws on NULL, so read
    // the JDBC-1 way: primitive get plus wasNull.
    private static Boolean readStoredBoolean(ResultSet rs, String column) throws SQLException {
        int raw = rs.getInt(column);
        return rs.wasNull() ? null : raw != 0;
    }

    private static String toStoredCustomFields(List<CaseCustomFieldDto> fields) {
        return fields == null ? null : OBJECT_MAPPER.writeValueAsString(fields);
    }

    /**
     * A row whose JSON can't be read (a damaged cache, or one written in a shape this version doesn't
     * know) reads as null, "not known", rather than failing every list read that includes it. The
     * JSON is bound to {@link CaseCustomFieldDto}'s component names, so renaming one needs a migration.
     */
    private static List<CaseCustomFieldDto> readStoredCustomFields(String json) {
        if (json == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, CUSTOM_FIELDS_TYPE);
        } catch (JacksonException unreadable) {
            return null;
        }
    }
}
