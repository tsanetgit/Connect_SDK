package com.tsanet.api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class CollaborationRequestRepositoryTest {
    private CollaborationRequestRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-collaboration-request-repo.db");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer.createSchema(jdbcTemplate);
        jdbcTemplate.execute("DELETE FROM collaboration_request");
        repository = new CollaborationRequestRepository(jdbcTemplate);
    }

    @Test
    void itStoresAndReadsCollaborationRequests() {
        repository.saveAll(
            List.of(
                new CollaborationRequestStatusDto(
                    1L,
                    "OPEN",
                    "Need help",
                    "Acme",
                    10L,
                    "Beta",
                    20L,
                    "tok1",
                    "2026-01-01T00:00:00Z",
                    "2026-01-02T00:00:00Z"
                )
            )
        );

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findByCompanyId(10L)).extracting(CollaborationRequestStatusDto::id).containsExactly(1L);
        assertThat(repository.findByCompanyId(99L)).isEmpty();
    }

    @Test
    void itUpdatesExistingRequestsOnConflict() {
        repository.saveAll(
            List.of(new CollaborationRequestStatusDto(1L, "OPEN", "Old", "Acme", 1L, "Beta", 2L, "tok1", null, null))
        );
        repository.saveAll(
            List.of(new CollaborationRequestStatusDto(1L, "CLOSED", "New", "Acme", 1L, "Beta", 2L, "tok1", null, null))
        );

        assertThat(repository.findAll())
            .singleElement()
            .extracting(CollaborationRequestStatusDto::status, CollaborationRequestStatusDto::summary)
            .containsExactly("CLOSED", "New");
    }
}
