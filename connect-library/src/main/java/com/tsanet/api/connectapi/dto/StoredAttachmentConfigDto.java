package com.tsanet.api.connectapi.dto;

public record StoredAttachmentConfigDto(String caseToken, AttachmentConfigDto config) {
    @Override
    public String toString() {
        return "StoredAttachmentConfigDto[<redacted>]";
    }
}
