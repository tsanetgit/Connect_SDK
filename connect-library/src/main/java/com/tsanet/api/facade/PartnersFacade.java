package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;

public interface PartnersFacade {
    List<PartnerSelectionDto> searchPartners(String searchTerm);

    List<PartnerSelectionDto> searchPartnersSemantic(String query, Integer limit);

    List<PartnerSelectionDto> listStoredPartners();

    List<PartnerSelectionDto> listStoredPartnersForSearchTerm(String searchTerm);
}
