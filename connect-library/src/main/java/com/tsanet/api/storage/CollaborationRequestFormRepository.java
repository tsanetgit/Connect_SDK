package com.tsanet.api.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class CollaborationRequestFormRepository {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<FormFieldDto>> FIELD_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;

    public CollaborationRequestFormRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(CollaborationRequestFormDto form) {
        String fetchedAt = Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO collaboration_request_form (
                receiver_company_id, document_id, custom_field_count, department_id, fields_json, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(receiver_company_id) DO UPDATE SET
                document_id = excluded.document_id,
                custom_field_count = excluded.custom_field_count,
                department_id = excluded.department_id,
                fields_json = excluded.fields_json,
                fetched_at = excluded.fetched_at
            """,
            form.receiverCompanyId(),
            form.documentId(),
            form.customFieldCount(),
            form.departmentId(),
            serializeFields(form.fields()),
            fetchedAt
        );
    }

    public List<CollaborationRequestFormDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT receiver_company_id, document_id, custom_field_count, department_id, fields_json
            FROM collaboration_request_form
            ORDER BY receiver_company_id
            """,
            ROW_MAPPER
        );
    }

    public List<CollaborationRequestFormDto> findByReceiverCompanyId(long receiverCompanyId) {
        return jdbcTemplate.query(
            """
            SELECT receiver_company_id, document_id, custom_field_count, department_id, fields_json
            FROM collaboration_request_form
            WHERE receiver_company_id = ?
            ORDER BY receiver_company_id
            """,
            ROW_MAPPER,
            receiverCompanyId
        );
    }

    public List<CollaborationRequestFormDto> findByDocumentId(long documentId) {
        return jdbcTemplate.query(
            """
            SELECT receiver_company_id, document_id, custom_field_count, department_id, fields_json
            FROM collaboration_request_form
            WHERE document_id = ?
            ORDER BY receiver_company_id
            """,
            ROW_MAPPER,
            documentId
        );
    }

    private static final RowMapper<CollaborationRequestFormDto> ROW_MAPPER =
        CollaborationRequestFormRepository::mapRow;

    private static CollaborationRequestFormDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CollaborationRequestFormDto(
            rs.getLong("receiver_company_id"),
            rs.getLong("document_id"),
            rs.getInt("custom_field_count"),
            rs.getObject("department_id", Long.class),
            deserializeFields(rs.getString("fields_json"))
        );
    }

    private static String serializeFields(List<FormFieldDto> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(fields);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize form fields", ex);
        }
    }

    private static List<FormFieldDto> deserializeFields(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(json, FIELD_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize form fields", ex);
        }
    }
}
