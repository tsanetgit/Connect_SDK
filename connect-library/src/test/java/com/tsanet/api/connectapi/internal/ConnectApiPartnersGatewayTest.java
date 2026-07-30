package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import com.tsanet.api.generated.api.EntitySearchApi;
import com.tsanet.api.generated.model.PartnerSelectionDTO;
import com.tsanet.api.storage.PartnerSelectionRepository;
import com.tsanet.api.storage.PartnerSelectionStorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiPartnersGatewayTest {
    @Mock
    private EntitySearchApi entitySearchApi;

    private ConnectApiPartnersGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("partners-gateway-test");
        PartnerSelectionStorageService storageService =
            new PartnerSelectionStorageService(new PartnerSelectionRepository(jdbcTemplate));
        gateway = new ConnectApiPartnersGateway(
            entitySearchApi,
            GatewayTestSupport.authenticatedSessionStore(),
            storageService
        );
    }

    @Test
    void itSearchesPartnersByKeywordAndPersistsResults() {
        PartnerSelectionDTO apiPartner = new PartnerSelectionDTO()
            .label("Beta Corp / Support")
            .companyName("Beta Corp")
            .companyId(2L)
            .documentId(100L);
        when(entitySearchApi.searchPartners("beta")).thenReturn(List.of(apiPartner));

        List<PartnerSelectionDto> partners = gateway.searchPartners(" beta ");

        assertThat(partners).singleElement().satisfies(partner -> {
            assertThat(partner.searchTerm()).isEqualTo("beta");
            assertThat(partner.label()).isEqualTo("Beta Corp / Support");
            assertThat(partner.companyId()).isEqualTo(2L);
        });
    }

    @Test
    void itRejectsBlankKeywordSearch() {
        assertThatThrownBy(() -> gateway.searchPartners("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itSearchesPartnersSemantically() {
        PartnerSelectionDTO apiPartner = new PartnerSelectionDTO()
            .label("Acme")
            .companyName("Acme Corp")
            .companyId(1L)
            .documentId(101L);
        when(entitySearchApi.searchPartnersSemanticSearch("network issue", 5)).thenReturn(List.of(apiPartner));

        List<PartnerSelectionDto> partners = gateway.searchPartnersSemantic("network issue", 5);

        assertThat(partners).singleElement().extracting(PartnerSelectionDto::companyId).isEqualTo(1L);
    }

    @Test
    void itRejectsInvalidSemanticLimit() {
        assertThatThrownBy(() -> gateway.searchPartnersSemantic("network issue", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
