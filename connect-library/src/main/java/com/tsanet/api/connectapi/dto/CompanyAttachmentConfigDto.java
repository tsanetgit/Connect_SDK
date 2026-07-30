package com.tsanet.api.connectapi.dto;

import java.util.Map;

public record CompanyAttachmentConfigDto(Long companyId, Map<String, Object> parameters) {
}
