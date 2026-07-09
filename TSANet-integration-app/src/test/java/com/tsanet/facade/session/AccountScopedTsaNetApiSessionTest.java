package com.tsanet.facade.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.storage.CollaborationRequestRepository;
import com.tsanet.api.storage.DatabaseInitializer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class AccountScopedTsaNetApiSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void itUsesSeparateSqliteFilesPerAccount() throws Exception {
        Path basePath = tempDir.resolve("cache.db");
        TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
            TsaNetApiConnectionSettings.of("http://localhost:8080", basePath.toString())
        );
        AccountScopedTsaNetApiSession session = new AccountScopedTsaNetApiSession(factory, Optional.empty());

        seedRepository(factory.sqlitePathForLabel("alpha-test.com"), 1L, "Alpha request");
        seedRepository(factory.sqlitePathForLabel("beta-test.com"), 2L, "Beta request");

        session.bindAccountForTesting("alpha@test.com", "secret");
        assertThat(session.collaborationRequests().listStoredRequests())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::summary)
            .isEqualTo("Alpha request");

        session.bindAccountForTesting("beta@test.com", "secret");
        assertThat(session.collaborationRequests().listStoredRequests())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::summary)
            .isEqualTo("Beta request");

        session.bindAccountForTesting("alpha@test.com", "secret");
        assertThat(session.collaborationRequests().listStoredRequests())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::summary)
            .isEqualTo("Alpha request");
    }

    @Test
    void itKeepsAccountCacheAfterLogout() {
        Path basePath = tempDir.resolve("cache.db");
        TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
            TsaNetApiConnectionSettings.of("http://localhost:8080", basePath.toString())
        );
        AccountScopedTsaNetApiSession session = new AccountScopedTsaNetApiSession(factory, Optional.empty());

        seedRepository(factory.sqlitePathForLabel("alpha-test.com"), 1L, "Persisted request");
        session.bindAccountForTesting("alpha@test.com", "secret");
        session.auth().logout();

        assertThat(session.auth().isAuthorized()).isFalse();
        assertThat(session.collaborationRequests().listStoredRequests()).hasSize(1);
    }

    @Test
    void itRequiresLoginBeforeRemoteOperationsWithoutActiveAccount() {
        AccountScopedTsaNetApiSession session = new AccountScopedTsaNetApiSession(
            TsaNetApi.sessionFactory(TsaNetApiConnectionSettings.of("http://localhost:8080", tempDir.resolve("x.db").toString())),
            Optional.empty()
        );

        assertThatThrownBy(() -> session.collaborationRequests().listStoredRequests())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("login");
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
