package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.generated.api.CaseAttachmentsApi;
import com.tsanet.api.generated.model.AttachmentConfigDTO;
import com.tsanet.api.generated.model.CompanyAttachmentConfigDTO;
import com.tsanet.api.storage.AttachmentConfigRepository;
import com.tsanet.api.storage.AttachmentConfigStorageService;
import com.tsanet.api.storage.AttachmentForwardResultRepository;
import com.tsanet.api.storage.AttachmentForwardResultStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiAttachmentsGatewayTest {
    @Mock
    private CaseAttachmentsApi caseAttachmentsApi;

    private ConnectApiAttachmentsGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("attachments-gateway-test");
        AttachmentConfigStorageService configStorageService =
            new AttachmentConfigStorageService(new AttachmentConfigRepository(jdbcTemplate));
        AttachmentForwardResultStorageService forwardStorageService =
            new AttachmentForwardResultStorageService(new AttachmentForwardResultRepository(jdbcTemplate));
        gateway = new ConnectApiAttachmentsGateway(
            caseAttachmentsApi,
            GatewayTestSupport.authenticatedSessionStore(),
            configStorageService,
            forwardStorageService
        );
    }

    @Test
    void itFetchesAttachmentConfigAndPersistsIt() {
        CompanyAttachmentConfigDTO submitter = new CompanyAttachmentConfigDTO().companyId(1L);
        AttachmentConfigDTO apiConfig = new AttachmentConfigDTO().submitter(submitter);
        when(caseAttachmentsApi.getAttachmentConfig("tok-att")).thenReturn(apiConfig);

        AttachmentConfigDto config = gateway.getAttachmentConfig("tok-att");

        assertThat(config.submitter().companyId()).isEqualTo(1L);
    }

    @Test
    void itRejectsInvalidForwardPayload() {
        assertThatThrownBy(() -> gateway.forwardAttachments("tok-att", "  ", java.util.List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
