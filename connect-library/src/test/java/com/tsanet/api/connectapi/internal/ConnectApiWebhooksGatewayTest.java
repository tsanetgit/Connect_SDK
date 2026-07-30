package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.WebhookDeliveryDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import com.tsanet.api.generated.api.WebhooksApi;
import com.tsanet.api.generated.api.WebhooksV1Api;
import com.tsanet.api.generated.model.CreateWebhookSubscriptionRequestDTO;
import com.tsanet.api.generated.model.WebhookDeliveryLogDTO;
import com.tsanet.api.generated.model.WebhookDeliveryLogPageDTO;
import com.tsanet.api.generated.model.WebhookEventType;
import com.tsanet.api.generated.model.WebhookSubscriptionDTO;
import com.tsanet.api.generated.model.WebhookSubscriptionResponseDTO;
import com.tsanet.api.storage.WebhookSubscriptionRepository;
import com.tsanet.api.storage.WebhookSubscriptionStorageService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiWebhooksGatewayTest {
    @Mock
    private WebhooksV1Api webhooksV1Api;
    @Mock
    private WebhooksApi webhooksApi;

    private WebhookSubscriptionStorageService storageService;
    private ConnectApiWebhooksGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("webhooks-gateway-test");
        storageService = new WebhookSubscriptionStorageService(new WebhookSubscriptionRepository(jdbcTemplate));
        gateway = new ConnectApiWebhooksGateway(
            webhooksV1Api,
            webhooksApi,
            GatewayTestSupport.authenticatedSessionStore(),
            storageService
        );
    }

    @Test
    void itListsWebhookSubscriptionsAndPersistsThem() {
        WebhookSubscriptionDTO apiSubscription = new WebhookSubscriptionDTO()
            .id(1L)
            .callbackUrl("https://example.com/hook")
            .eventTypes(List.of(WebhookEventType.COLLABORATION_REQUEST_CREATED))
            .active(true);
        when(webhooksV1Api.listWebhookSubscriptions()).thenReturn(List.of(apiSubscription));

        List<WebhookSubscriptionDto> subscriptions = gateway.listWebhookSubscriptions();

        assertThat(subscriptions).singleElement().satisfies(subscription -> {
            assertThat(subscription.id()).isEqualTo(1L);
            assertThat(subscription.callbackUrl()).isEqualTo("https://example.com/hook");
            assertThat(subscription.eventTypes()).isEqualTo("COLLABORATION_REQUEST_CREATED");
        });
        assertThat(storageService.findAll()).hasSize(1);
    }

    @Test
    void itCreatesWebhookSubscriptionAndStoresSecret() {
        WebhookSubscriptionResponseDTO created = new WebhookSubscriptionResponseDTO()
            .id(5L)
            .callbackUrl("https://example.com/new")
            .secret("hmac-secret")
            .active(true);
        when(webhooksV1Api.createWebhookSubscription(any(CreateWebhookSubscriptionRequestDTO.class))).thenReturn(created);
        when(webhooksV1Api.listWebhookSubscriptions()).thenReturn(List.of());

        WebhookSubscriptionResponseDto response = gateway.createWebhookSubscription(
            "https://example.com/new",
            List.of("collaboration-request.created")
        );

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.secret()).isEqualTo("hmac-secret");

        ArgumentCaptor<CreateWebhookSubscriptionRequestDTO> captor =
            ArgumentCaptor.forClass(CreateWebhookSubscriptionRequestDTO.class);
        verify(webhooksV1Api).createWebhookSubscription(captor.capture());
        assertThat(captor.getValue().getCallbackUrl().toString()).isEqualTo("https://example.com/new");
    }

    @Test
    void itMapsWebhookDeliveryLogs() {
        WebhookDeliveryLogDTO delivery = new WebhookDeliveryLogDTO()
            .id(10L)
            .cloudEventId("evt-123")
            .eventType("collaboration-request.created")
            .httpStatus(200)
            .attemptNumber(1)
            .success(true)
            .createdAt(OffsetDateTime.parse("2026-01-04T10:00:00Z"));
        WebhookDeliveryLogPageDTO page = new WebhookDeliveryLogPageDTO()
            .content(List.of(delivery))
            .totalElements(1L)
            .totalPages(1)
            .size(20)
            .number(0);
        when(webhooksApi.getWebhookDeliveries(5L, 0, 20)).thenReturn(page);

        List<WebhookDeliveryDto> deliveries = gateway.listWebhookDeliveries(5L, 0, 20).content();

        assertThat(deliveries).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(10L);
            assertThat(item.cloudEventId()).isEqualTo("evt-123");
            assertThat(item.success()).isTrue();
        });
    }

    @Test
    void itDeletesWebhookSubscriptionAndRefreshesCache() {
        when(webhooksV1Api.listWebhookSubscriptions()).thenReturn(List.of());

        gateway.deleteWebhookSubscription(7L);

        verify(webhooksApi).deleteWebhookSubscription(7L);
        verify(webhooksV1Api).listWebhookSubscriptions();
    }
}
