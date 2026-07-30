package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.WebhookDeliveryPageDto;
import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import com.tsanet.api.connectapi.dto.WebhookInboundResultDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import java.util.List;

public interface WebhooksFacade {
    List<WebhookSubscriptionDto> listSubscriptions();

    List<WebhookSubscriptionDto> listStoredSubscriptions();

    WebhookSubscriptionResponseDto createSubscription(String callbackUrl, List<String> eventTypes);

    void deleteSubscription(Long id);

    WebhookDeliveryPageDto listDeliveries(long subscriptionId, int page, int size);

    List<WebhookInboundEventDto> listStoredInboundEvents();

    WebhookInboundResultDto receiveInbound(String signatureHeader, String rawBody);
}
