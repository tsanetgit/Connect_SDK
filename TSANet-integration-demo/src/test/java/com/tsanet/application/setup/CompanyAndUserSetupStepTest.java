package com.tsanet.application.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.application.setup.step.CompanyAndUserSetupStep;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CompanyAndUserSetupStepTest {
    @Test
    void itUsesInsertSelectWhereNotExistsForCompaniesAndUsers() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

        new CompanyAndUserSetupStep().apply(jdbc);

        assertThat(jdbc.updates).hasSize(6);
        assertThat(jdbc.updates.subList(0, 4))
            .allSatisfy(sql -> assertThat(sql).containsIgnoringCase("WHERE NOT EXISTS"));
        assertThat(jdbc.updates.get(0)).contains("slug = 'acme'");
        assertThat(jdbc.updates.get(1)).contains("slug = 'beta'");
        assertThat(jdbc.updates.get(2)).contains("LOWER(email) = LOWER(?)");
        assertThat(jdbc.updates.subList(4, 6))
            .allSatisfy(sql -> assertThat(sql).containsIgnoringCase("UPDATE users SET company_id"));
        assertThat(jdbc.executes).hasSize(2);
        assertThat(jdbc.executes).allSatisfy(sql -> assertThat(sql).contains("setval("));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> updates = new ArrayList<>();
        private final List<String> executes = new ArrayList<>();

        private RecordingJdbcTemplate() {
            super(unusedDataSource());
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 0;
        }

        @Override
        public void execute(String sql) {
            executes.add(sql);
        }

        private static DataSource unusedDataSource() {
            return new DataSource() {
                @Override
                public java.sql.Connection getConnection() {
                    throw new UnsupportedOperationException("test fake does not connect");
                }

                @Override
                public java.sql.Connection getConnection(String username, String password) {
                    throw new UnsupportedOperationException("test fake does not connect");
                }

                @Override
                public java.io.PrintWriter getLogWriter() {
                    return null;
                }

                @Override
                public void setLogWriter(java.io.PrintWriter out) {
                }

                @Override
                public void setLoginTimeout(int seconds) {
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public java.util.logging.Logger getParentLogger() {
                    return java.util.logging.Logger.getLogger(java.util.logging.Logger.GLOBAL_LOGGER_NAME);
                }

                @Override
                public <T> T unwrap(Class<T> iface) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }
            };
        }
    }
}
