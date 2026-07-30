package com.tsanet.api.connectapi.dto;

public record AttachmentConfigDto(
    CompanyAttachmentConfigDto submitter,
    CompanyAttachmentConfigDto receiver
) {
}
