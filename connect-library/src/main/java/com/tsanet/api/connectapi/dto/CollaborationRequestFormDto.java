package com.tsanet.api.connectapi.dto;

import java.util.List;

public record CollaborationRequestFormDto(
    long receiverCompanyId,
    long documentId,
    int customFieldCount,
    Long departmentId,
    List<FormFieldDto> fields
) {
    public CollaborationRequestFormDto(long receiverCompanyId, long documentId, int customFieldCount) {
        this(receiverCompanyId, documentId, customFieldCount, null, List.of());
    }
}
