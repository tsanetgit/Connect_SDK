package com.tsanet.api.connectapi.dto;

public record CaseResponseDto(
    Long id,
    String caseToken,
    String type,
    String caseNumber,
    String engineerName,
    String engineerPhone,
    String engineerEmail,
    String nextSteps,
    String createdAt
) {
    @Override
    public String toString() {
        return "CaseResponseDto[id=" + id + ", caseToken=<redacted>, type=" + type
            + ", createdAt=" + createdAt + "]";
    }
}
