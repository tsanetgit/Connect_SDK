package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class AttachmentForwardResultRepository {
    private static final RowMapper<StoredAttachmentForwardResultDto> ROW_MAPPER =
        AttachmentForwardResultRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public AttachmentForwardResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(String caseToken, String description, List<AttachmentForwardResultDto> results) {
        if (results.isEmpty()) {
            return;
        }

        String forwardedAt = Instant.now().toString();
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO attachment_forward_result (
                case_token, description, file_name, receiver_status, receiver_message,
                submitter_status, submitter_message, complete_success, partial_success, forwarded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            results,
            results.size(),
            (ps, result) -> {
                ps.setString(1, caseToken);
                ps.setString(2, description);
                ps.setString(3, result.fileName());
                ps.setString(4, result.receiverStatus());
                ps.setString(5, result.receiverMessage());
                ps.setString(6, result.submitterStatus());
                ps.setString(7, result.submitterMessage());
                ps.setObject(8, result.completeSuccess() != null && result.completeSuccess() ? 1 : 0);
                ps.setObject(9, result.partialSuccess() != null && result.partialSuccess() ? 1 : 0);
                ps.setString(10, forwardedAt);
            }
        );
    }

    public List<StoredAttachmentForwardResultDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT case_token, description, file_name, receiver_status, receiver_message,
                   submitter_status, submitter_message, complete_success, partial_success
            FROM attachment_forward_result
            ORDER BY row_id
            """,
            ROW_MAPPER
        );
    }

    public List<StoredAttachmentForwardResultDto> findByCaseToken(String caseToken) {
        return jdbcTemplate.query(
            """
            SELECT case_token, description, file_name, receiver_status, receiver_message,
                   submitter_status, submitter_message, complete_success, partial_success
            FROM attachment_forward_result
            WHERE case_token = ?
            ORDER BY row_id
            """,
            ROW_MAPPER,
            caseToken
        );
    }

    private static StoredAttachmentForwardResultDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StoredAttachmentForwardResultDto(
            rs.getString("case_token"),
            rs.getString("description"),
            rs.getString("file_name"),
            rs.getString("receiver_status"),
            rs.getString("receiver_message"),
            rs.getString("submitter_status"),
            rs.getString("submitter_message"),
            rs.getObject("complete_success", Integer.class) != null && rs.getInt("complete_success") == 1,
            rs.getObject("partial_success", Integer.class) != null && rs.getInt("partial_success") == 1
        );
    }
}
