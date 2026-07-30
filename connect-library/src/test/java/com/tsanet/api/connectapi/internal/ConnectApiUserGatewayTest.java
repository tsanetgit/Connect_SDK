package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.model.CompanyDTO;
import com.tsanet.api.generated.model.UserContextDTO;
import com.tsanet.api.generated.model.UserDTO;
import com.tsanet.api.storage.UserContextRepository;
import com.tsanet.api.storage.UserContextStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiUserGatewayTest {
    @Mock
    private IdentityApi identityApi;

    private UserContextStorageService storageService;
    private ConnectApiUserGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("user-gateway-test");
        storageService = new UserContextStorageService(new UserContextRepository(jdbcTemplate));
        gateway = new ConnectApiUserGateway(
            identityApi,
            GatewayTestSupport.authenticatedSessionStore(),
            storageService
        );
    }

    @Test
    void itMapsCurrentUserAndPersistsContext() {
        UserContextDTO apiUser = new UserContextDTO()
            .company(new CompanyDTO().id(10L).name("Acme"))
            .user(new UserDTO().id(5L).username("api@test.com").email("api@test.com").firstName("Api").lastName("User"));
        when(identityApi.getCurrentUser()).thenReturn(apiUser);

        UserContextDto user = gateway.getCurrentUser();

        assertThat(user.companyId()).isEqualTo(10L);
        assertThat(user.companyName()).isEqualTo("Acme");
        assertThat(user.userId()).isEqualTo(5L);
        assertThat(user.username()).isEqualTo("api@test.com");
        assertThat(storageService.findAll()).singleElement().isEqualTo(user);
        verify(identityApi).getCurrentUser();
    }
}
