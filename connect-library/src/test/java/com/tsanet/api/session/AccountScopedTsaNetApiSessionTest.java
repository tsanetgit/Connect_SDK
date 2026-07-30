package com.tsanet.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.storage.CollaborationRequestRepository;
import com.tsanet.api.storage.DatabaseInitializer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class AccountScopedTsaNetApiSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void itUsesConfiguredSqlitePathsPerApplicationUser() {
        Path alphaDb = tempDir.resolve("alpha.db");
        Path betaDb = tempDir.resolve("beta.db");
        ApplicationUserAccountRegistry registry = ApplicationUserAccountRegistry.of(
            List.of(
                ApplicationUserAccount.passwordAccount("alpha", alphaDb.toString(), "alpha@test.com", "secret"),
                ApplicationUserAccount.passwordAccount("beta", betaDb.toString(), "beta@test.com", "secret")
            )
        );
        TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
            TsaNetApiConnectionSettings.of("http://localhost:8080", tempDir.resolve("unused.db").toString())
        );
        AccountScopedTsaNetApiSession session = new AccountScopedTsaNetApiSession(factory, registry);

        seedRepository(alphaDb.toString(), 1L, "Alpha request");
        seedRepository(betaDb.toString(), 2L, "Beta request");

        session.bindAccountForTesting("alpha");
        assertThat(session.activeAccountLabel()).contains("alpha");
        assertThat(session.activeSqlitePath()).contains(alphaDb.toString());
        assertThat(session.collaborationRequests().listStoredRequests())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::summary)
            .isEqualTo("Alpha request");

        session.bindAccountForTesting("beta");
        assertThat(session.activeAccountLabel()).contains("beta");
        assertThat(session.collaborationRequests().listStoredRequests())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::summary)
            .isEqualTo("Beta request");
    }

    @Test
    void itRejectsUnknownApplicationUsers() {
        AccountScopedTsaNetApiSession session = new AccountScopedTsaNetApiSession(
            TsaNetApi.sessionFactory(TsaNetApiConnectionSettings.of("http://localhost:8080", tempDir.resolve("x.db").toString())),
            ApplicationUserAccountRegistry.empty()
        );

        assertThatThrownBy(() -> session.auth().login("unknown@test.com", "secret"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown application user");
    }

    private static void seedRepository(String sqlitePath, long id, String summary) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + Path.of(sqlitePath).toAbsolutePath());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer.createSchema(jdbcTemplate);
        new CollaborationRequestRepository(jdbcTemplate).saveAll(
            List.of(new CollaborationRequestStatusDto(id, "OPEN", summary, "A", 1L, "B", 2L, "tok" + id, null, null))
        );
    }
}
