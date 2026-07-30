package com.tsanet.api.connectapi.dto;

import java.util.List;

public record WebhookDeliveryPageDto(
    List<WebhookDeliveryDto> content,
    long totalElements,
    int totalPages,
    int size,
    int number
) {
}
