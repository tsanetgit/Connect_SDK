package com.tsanet.application.setup;

import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSetupSupport {
    private JdbcSetupSupport() {
    }

    public static boolean exists(JdbcTemplate jdbc, String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    public static void insertIfNotExists(JdbcTemplate jdbc, String insertSelectSql, Object... args) {
        jdbc.update(insertSelectSql, args);
    }

    public static void resetSequence(JdbcTemplate jdbc, String sequenceName, String tableName) {
        jdbc.execute(
            "SELECT setval('" + sequenceName + "', (SELECT COALESCE(MAX(id), 1) FROM " + tableName + "))"
        );
    }
}
