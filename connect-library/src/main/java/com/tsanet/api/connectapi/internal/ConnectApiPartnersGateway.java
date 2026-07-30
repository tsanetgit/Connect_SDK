package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.PartnerSearchValidation;
import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import com.tsanet.api.generated.api.EntitySearchApi;
import com.tsanet.api.generated.model.PartnerSelectionDTO;
import com.tsanet.api.storage.PartnerSelectionStorageService;
import java.util.Collections;
import java.util.List;

public class ConnectApiPartnersGateway {
    private final EntitySearchApi entitySearchApi;
    private final ConnectApiSessionStore sessionStore;
    private final PartnerSelectionStorageService storageService;

    public ConnectApiPartnersGateway(
        EntitySearchApi entitySearchApi,
        ConnectApiSessionStore sessionStore,
        PartnerSelectionStorageService storageService
    ) {
        this.entitySearchApi = entitySearchApi;
        this.sessionStore = sessionStore;
        this.storageService = storageService;
    }

    public List<PartnerSelectionDto> searchPartners(String searchTerm) {
        requireLogin();

        PartnerSearchValidation.ValidationResult validation = PartnerSearchValidation.validateKeywordSearch(searchTerm);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }

        String normalizedTerm = searchTerm.strip();
        List<PartnerSelectionDTO> body = entitySearchApi.searchPartners(normalizedTerm);
        if (body == null) {
            return Collections.emptyList();
        }
        List<PartnerSelectionDto> partners = body.stream().map(partner -> toDto(partner, normalizedTerm)).toList();
        storageService.storeFetched(normalizedTerm, partners);
        return partners;
    }

    public List<PartnerSelectionDto> searchPartnersSemantic(String query, Integer limit) {
        requireLogin();

        PartnerSearchValidation.ValidationResult queryValidation = PartnerSearchValidation.validateSemanticQuery(query);
        if (!queryValidation.valid()) {
            throw new IllegalArgumentException(queryValidation.message());
        }
        PartnerSearchValidation.ValidationResult limitValidation = PartnerSearchValidation.validateSemanticLimit(limit);
        if (!limitValidation.valid()) {
            throw new IllegalArgumentException(limitValidation.message());
        }

        String normalizedQuery = query.strip();
        int effectiveLimit = limit != null ? limit : PartnerSearchValidation.DEFAULT_SEMANTIC_LIMIT;
        List<PartnerSelectionDTO> body = entitySearchApi.searchPartnersSemanticSearch(normalizedQuery, effectiveLimit);
        if (body == null) {
            return Collections.emptyList();
        }
        List<PartnerSelectionDto> partners = body.stream().map(partner -> toDto(partner, normalizedQuery)).toList();
        storageService.storeFetched(normalizedQuery, partners);
        return partners;
    }

    private PartnerSelectionDto toDto(PartnerSelectionDTO dto, String searchTerm) {
        return new PartnerSelectionDto(
            searchTerm,
            dto.getLabel(),
            dto.getCompanyName(),
            dto.getDepartmentName(),
            dto.getCompanyId(),
            dto.getDepartmentId(),
            dto.getDocumentId()
        );
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}
