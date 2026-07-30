package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class PartnerSelectionRepository {
    private static final RowMapper<PartnerSelectionDto> ROW_MAPPER = PartnerSelectionRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public PartnerSelectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void replaceForSearchTerm(String searchTerm, List<PartnerSelectionDto> partners) {
        jdbcTemplate.update("DELETE FROM partner_selection WHERE search_term = ?", searchTerm);
        if (partners.isEmpty()) {
            return;
        }

        String fetchedAt = Instant.now().toString();
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO partner_selection (
                search_term, label, company_name, department_name,
                company_id, department_id, document_id, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            partners,
            partners.size(),
            (ps, partner) -> {
                ps.setString(1, partner.searchTerm());
                ps.setString(2, partner.label());
                ps.setString(3, partner.companyName());
                ps.setString(4, partner.departmentName());
                ps.setObject(5, partner.companyId());
                ps.setObject(6, partner.departmentId());
                ps.setObject(7, partner.documentId());
                ps.setString(8, fetchedAt);
            }
        );
    }

    public List<PartnerSelectionDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT search_term, label, company_name, department_name,
                   company_id, department_id, document_id
            FROM partner_selection
            ORDER BY search_term, row_id
            """,
            ROW_MAPPER
        );
    }

    public List<PartnerSelectionDto> findBySearchTerm(String searchTerm) {
        return jdbcTemplate.query(
            """
            SELECT search_term, label, company_name, department_name,
                   company_id, department_id, document_id
            FROM partner_selection
            WHERE search_term = ?
            ORDER BY row_id
            """,
            ROW_MAPPER,
            searchTerm
        );
    }

    private static PartnerSelectionDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PartnerSelectionDto(
            rs.getString("search_term"),
            rs.getString("label"),
            rs.getString("company_name"),
            rs.getString("department_name"),
            rs.getObject("company_id", Long.class),
            rs.getObject("department_id", Long.class),
            rs.getObject("document_id", Long.class)
        );
    }
}
