package com.tsanet.api.connectapi.dto;

import java.util.List;

public record CollaborationRequestFormTemplateDto(
    long documentId,
    Long receiverCompanyId,
    Long departmentId,
    List<FormFieldDto> fields
) {
    public int customFieldCount() {
        return fields != null ? fields.size() : 0;
    }

    public CollaborationRequestFormDto toStoredMetadata(long receiverCompanyId) {
        return new CollaborationRequestFormDto(
            receiverCompanyId,
            documentId,
            customFieldCount(),
            departmentId,
            fields
        );
    }
}
