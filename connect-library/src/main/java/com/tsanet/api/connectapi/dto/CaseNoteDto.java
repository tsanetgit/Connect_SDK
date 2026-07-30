package com.tsanet.api.connectapi.dto;

public record CaseNoteDto(
    Long id,
    Long caseId,
    String caseToken,
    String companyName,
    String creatorUsername,
    String creatorEmail,
    String creatorName,
    String summary,
    String description,
    String priority,
    String status,
    String token,
    String createdAt,
    String updatedAt
) {
}
