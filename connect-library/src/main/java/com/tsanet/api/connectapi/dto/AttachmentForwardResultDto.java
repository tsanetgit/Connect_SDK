package com.tsanet.api.connectapi.dto;

public record AttachmentForwardResultDto(
    String fileName,
    String receiverStatus,
    String receiverMessage,
    String submitterStatus,
    String submitterMessage,
    Boolean completeSuccess,
    Boolean partialSuccess
) {
}
