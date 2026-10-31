package com.tsanet.api.connectapi.dto;

import java.util.Map;

public record CompanyAttachmentConfigDto(Long companyId, Map<String, Object> parameters) {
    /** Keys only: the map is opaque, and a platform's parameters can carry credentials. */
    @Override
    public String toString() {
        return "CompanyAttachmentConfigDto[companyId=" + companyId + ", parameters="
            + (parameters == null ? null : parameters.keySet()) + "]";
    }
}
