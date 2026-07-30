package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CompanyAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentConfigDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class AttachmentConfigRepository {
    private static final RowMapper<StoredAttachmentConfigDto> ROW_MAPPER = AttachmentConfigRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public AttachmentConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String caseToken, AttachmentConfigDto config) {
        String fetchedAt = Instant.now().toString();
        Long submitterCompanyId = config.submitter() != null ? config.submitter().companyId() : null;
        Long receiverCompanyId = config.receiver() != null ? config.receiver().companyId() : null;
        jdbcTemplate.update(
            """
            INSERT INTO attachment_config (
                case_token, submitter_company_id, receiver_company_id, fetched_at
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT(case_token) DO UPDATE SET
                submitter_company_id = excluded.submitter_company_id,
                receiver_company_id = excluded.receiver_company_id,
                fetched_at = excluded.fetched_at
            """,
            caseToken,
            submitterCompanyId,
            receiverCompanyId,
            fetchedAt
        );
    }

    public List<StoredAttachmentConfigDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT case_token, submitter_company_id, receiver_company_id
            FROM attachment_config
            ORDER BY case_token
            """,
            ROW_MAPPER
        );
    }

    public List<StoredAttachmentConfigDto> findByCaseToken(String caseToken) {
        return jdbcTemplate.query(
            """
            SELECT case_token, submitter_company_id, receiver_company_id
            FROM attachment_config
            WHERE case_token = ?
            ORDER BY case_token
            """,
            ROW_MAPPER,
            caseToken
        );
    }

    private static StoredAttachmentConfigDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        Long submitterCompanyId = rs.getObject("submitter_company_id", Long.class);
        Long receiverCompanyId = rs.getObject("receiver_company_id", Long.class);
        return new StoredAttachmentConfigDto(
            rs.getString("case_token"),
            new AttachmentConfigDto(
                submitterCompanyId != null
                    ? new CompanyAttachmentConfigDto(submitterCompanyId, Collections.emptyMap())
                    : null,
                receiverCompanyId != null
                    ? new CompanyAttachmentConfigDto(receiverCompanyId, Collections.emptyMap())
                    : null
            )
        );
    }
}
