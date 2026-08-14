package com.tsanet.demo.web;

import com.tsanet.api.connectapi.dto.WebhookDeliveryPageDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhooksController {

    private final SessionGuard guard;

    public WebhooksController(SessionGuard guard) {
        this.guard = guard;
    }

    @GetMapping("/api/webhooks")
    public List<WebhookSubscriptionDto> listSubscriptions() {
        return guard.session().webhooks().listSubscriptions();
    }

    @PostMapping("/api/webhooks")
    public WebhookSubscriptionResponseDto createSubscription(@RequestBody CreateSubscriptionBody body) {
        return guard.session().webhooks().createSubscription(body.callbackUrl(), body.eventTypes());
    }

    @DeleteMapping("/api/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(@PathVariable Long id) {
        guard.session().webhooks().deleteSubscription(id);
    }

    @GetMapping("/api/webhooks/{id}/deliveries")
    public WebhookDeliveryPageDto listDeliveries(
        @PathVariable long id,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return guard.session().webhooks().listDeliveries(id, page, size);
    }

    public record CreateSubscriptionBody(String callbackUrl, List<String> eventTypes) {
    }
}
