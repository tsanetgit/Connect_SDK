package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.UserContextDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class UserContextRepository {
    private static final RowMapper<UserContextDto> ROW_MAPPER = UserContextRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public UserContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UserContextDto userContext) {
        String fetchedAt = Instant.now().toString();
        jdbcTemplate.update(
            """
            INSERT INTO user_context (
                id, company_id, company_name, user_id, username, email, first_name, last_name, fetched_at
            ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                company_id = excluded.company_id,
                company_name = excluded.company_name,
                user_id = excluded.user_id,
                username = excluded.username,
                email = excluded.email,
                first_name = excluded.first_name,
                last_name = excluded.last_name,
                fetched_at = excluded.fetched_at
            """,
            userContext.companyId(),
            userContext.companyName(),
            userContext.userId(),
            userContext.username(),
            userContext.email(),
            userContext.firstName(),
            userContext.lastName(),
            fetchedAt
        );
    }

    public List<UserContextDto> findAll() {
        return jdbcTemplate.query(
            """
            SELECT company_id, company_name, user_id, username, email, first_name, last_name
            FROM user_context
            WHERE id = 1
            """,
            ROW_MAPPER
        );
    }

    private static UserContextDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new UserContextDto(
            rs.getObject("company_id", Long.class),
            rs.getString("company_name"),
            rs.getObject("user_id", Long.class),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("first_name"),
            rs.getString("last_name")
        );
    }
}
