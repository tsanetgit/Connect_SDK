package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import com.tsanet.facade.config.WebhookProperties;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WebhookSubscriptionExecutor {
    private static final List<String> DEFAULT_EVENT_TYPES = List.of(
        "collaboration-request.created",
        "note.created"
    );

    private final TsaNetApiSession session;
    private final WebhookProperties webhookProperties;

    public WebhookSubscriptionExecutor(TsaNetApiSession session, WebhookProperties webhookProperties) {
        this.session = session;
        this.webhookProperties = webhookProperties;
    }

    public void create(String[] args, CliRunContext cliRunContext) {
        session.auth().loginWithConfiguredCredentials();

        String callbackUrl = CliArgs.callbackUrl(args).orElseGet(webhookProperties::callbackUrl);
        List<String> eventTypes = CliArgs.eventTypes(args).orElse(DEFAULT_EVENT_TYPES);

        WebhookSubscriptionResponseDto created = session.webhooks().createSubscription(callbackUrl, eventTypes);
        System.out.println(
            EntityPrinter.info(
                cliRunContext,
                "Webhook registered: id="
                    + created.id()
                    + " callback="
                    + created.callbackUrl()
                    + " events="
                    + created.eventTypes()
                    + " secretStored="
                    + (created.secret() != null && !created.secret().isBlank())
            )
        );
    }

    public void delete(String[] args, CliRunContext cliRunContext) {
        session.auth().loginWithConfiguredCredentials();
        long id = CliArgs.webhookId(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --id WEBHOOK_ID"));
        session.webhooks().deleteSubscription(id);
        System.out.println(EntityPrinter.info(cliRunContext, "Webhook subscription deleted: id=" + id));
    }

    public void deliveries(String[] args, CliRunContext cliRunContext) {
        session.auth().loginWithConfiguredCredentials();
        long id = CliArgs.webhookId(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --id WEBHOOK_ID"));
        int page = CliArgs.page(args).orElse(0);
        int size = CliArgs.size(args).orElse(20);

        var pageResult = session.webhooks().listDeliveries(id, page, size);
        EntityPrinter.printWebhookDeliveries(cliRunContext, "Webhook deliveries for id=" + id, pageResult);
    }
}
