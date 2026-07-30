package com.tsanet.api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import com.tsanet.api.connectapi.dto.CompanyAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class AttachmentAndFormRepositoryTest {
    private CollaborationRequestFormRepository formRepository;
    private AttachmentConfigRepository attachmentConfigRepository;
    private AttachmentForwardResultRepository attachmentForwardResultRepository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:target/test-attachment-form-repo.db");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer.createSchema(jdbcTemplate);
        jdbcTemplate.execute("DELETE FROM collaboration_request_form");
        jdbcTemplate.execute("DELETE FROM attachment_config");
        jdbcTemplate.execute("DELETE FROM attachment_forward_result");
        formRepository = new CollaborationRequestFormRepository(jdbcTemplate);
        attachmentConfigRepository = new AttachmentConfigRepository(jdbcTemplate);
        attachmentForwardResultRepository = new AttachmentForwardResultRepository(jdbcTemplate);
    }

    @Test
    void itStoresAndReadsCollaborationRequestForms() {
        var fields = List.of(new FormFieldDto(1L, "DETAILS", "Serial", "TEXT", 1, true, null, null, "SN-1"));
        formRepository.save(new CollaborationRequestFormDto(20L, 100L, 1, 30L, fields));

        assertThat(formRepository.findAll())
            .singleElement()
            .isEqualTo(new CollaborationRequestFormDto(20L, 100L, 1, 30L, fields));
        assertThat(formRepository.findByReceiverCompanyId(20L)).hasSize(1);
        assertThat(formRepository.findByDocumentId(100L)).hasSize(1);
        assertThat(formRepository.findByReceiverCompanyId(99L)).isEmpty();
    }

    @Test
    void itStoresAndReadsAttachmentConfigAndForwardResults() {
        attachmentConfigRepository.save(
            "case-token",
            new AttachmentConfigDto(
                new CompanyAttachmentConfigDto(10L, Map.of()),
                new CompanyAttachmentConfigDto(20L, Map.of())
            )
        );
        attachmentForwardResultRepository.saveAll(
            "case-token",
            "log file",
            List.of(
                new AttachmentForwardResultDto(
                    "report.txt",
                    "SUCCESS",
                    "ok",
                    "SUCCESS",
                    "ok",
                    true,
                    false
                )
            )
        );

        assertThat(attachmentConfigRepository.findByCaseToken("case-token"))
            .singleElement()
            .satisfies(stored -> {
                assertThat(stored.caseToken()).isEqualTo("case-token");
                assertThat(stored.config().submitter().companyId()).isEqualTo(10L);
                assertThat(stored.config().receiver().companyId()).isEqualTo(20L);
            });
        assertThat(attachmentForwardResultRepository.findByCaseToken("case-token"))
            .singleElement()
            .extracting(
                result -> result.fileName(),
                result -> result.description(),
                result -> result.completeSuccess()
            )
            .containsExactly("report.txt", "log file", true);
    }
}
