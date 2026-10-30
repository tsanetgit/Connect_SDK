package com.tsanet.api.connectapi.dto;

public record NormalizedHttpsAttachmentConfigDto(
    String domain,
    String password,
    String expiration,
    String httpsPath,
    Integer httpsPort
) {
    @Override
    public String toString() {
        return "NormalizedHttpsAttachmentConfigDto[<redacted>]";
    }
}
