package com.tsanet.api.connectapi.internal;

import com.tsanet.api.generated.model.CaseStatus;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.storage.DatabaseInitializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

final class GatewayTestSupport {
    private static final Path TEST_DB_DIR = Path.of("target", "gateway-test-dbs");
    private static final ThreadLocal<List<Path>> CREATED_DATABASES = ThreadLocal.withInitial(ArrayList::new);

    private GatewayTestSupport() {
    }

    static ConnectApiSessionStore authenticatedSessionStore() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        sessionStore.savePassword("api@test.com", "test-token");
        return sessionStore;
    }

    static JdbcTemplate inMemoryJdbc(String dbName) {
        try {
            Files.createDirectories(TEST_DB_DIR);
            Path dbPath = TEST_DB_DIR.resolve(dbName + "-" + System.nanoTime() + ".db").toAbsolutePath().normalize();
            CREATED_DATABASES.get().add(dbPath);

            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + dbPath);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            DatabaseInitializer.createSchema(jdbcTemplate);
            return jdbcTemplate;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize test SQLite database", ex);
        }
    }

    static void cleanupCreatedDatabases() {
        for (Path dbPath : CREATED_DATABASES.get()) {
            deleteSqliteFiles(dbPath);
        }
        CREATED_DATABASES.get().clear();
    }

    private static void deleteSqliteFiles(Path dbPath) {
        deleteIfExists(dbPath);
        deleteIfExists(Path.of(dbPath + "-journal"));
        deleteIfExists(Path.of(dbPath + "-wal"));
        deleteIfExists(Path.of(dbPath + "-shm"));
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    static CollaborationRequestStatusDTO sampleCollaborationRequest(String token) {
        CollaborationRequestStatusDTO dto = new CollaborationRequestStatusDTO();
        dto.setId(42L);
        dto.setStatus(CaseStatus.OPEN);
        dto.setSummary("Need help");
        dto.setSubmitCompanyName("Acme");
        dto.setSubmitCompanyId(1L);
        dto.setReceiveCompanyName("Beta");
        dto.setReceiveCompanyId(2L);
        dto.setToken(token);
        dto.setCreatedAt(OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        dto.setUpdatedAt(OffsetDateTime.parse("2026-01-02T10:00:00Z"));
        return dto;
    }
}
