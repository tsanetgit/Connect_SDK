package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.generated.api.FormRequestApi;
import com.tsanet.api.generated.model.CollaborationRequestDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectApiFormGatewayTest {
    @Mock
    private FormRequestApi formRequestApi;

    @Test
    void itFetchesFormByCompanyId() {
        CollaborationRequestDTO form = new CollaborationRequestDTO().documentId(100L);
        when(formRequestApi.getFormByCompanyId(2L)).thenReturn(form);

        ConnectApiFormGateway gateway = new ConnectApiFormGateway(
            formRequestApi,
            GatewayTestSupport.authenticatedSessionStore()
        );

        assertThat(gateway.getFormByCompanyId(2L).getDocumentId()).isEqualTo(100L);
        verify(formRequestApi).getFormByCompanyId(2L);
    }

    @Test
    void itRequiresLogin() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiFormGateway gateway = new ConnectApiFormGateway(formRequestApi, sessionStore);

        assertThatThrownBy(() -> gateway.getFormByDepartmentId(3L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not logged in");
    }

    @Test
    void itRejectsEmptyFormResponse() {
        when(formRequestApi.getFormByDocumentId(9L)).thenReturn(null);

        ConnectApiFormGateway gateway = new ConnectApiFormGateway(
            formRequestApi,
            GatewayTestSupport.authenticatedSessionStore()
        );

        assertThatThrownBy(() -> gateway.getFormByDocumentId(9L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("document id=9");
    }
}
