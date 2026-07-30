package com.tsanet.api.connectapi.dto;

public record PartnerSelectionDto(
    String searchTerm,
    String label,
    String companyName,
    String departmentName,
    Long companyId,
    Long departmentId,
    Long documentId
) {
}
