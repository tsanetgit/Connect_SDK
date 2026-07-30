package com.tsanet.api.connectapi.dto;

public record StoredAttachmentForwardResultDto(
    String caseToken,
    String description,
    String fileName,
    String receiverStatus,
    String receiverMessage,
    String submitterStatus,
    String submitterMessage,
    Boolean completeSuccess,
    Boolean partialSuccess
) {
}
