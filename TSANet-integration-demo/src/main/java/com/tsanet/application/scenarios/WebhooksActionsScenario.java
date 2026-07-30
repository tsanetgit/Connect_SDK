package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.SCENARIO_WEBHOOK_CALLBACK_URL;
import static com.tsanet.application.setup.TestScenarioDataCatalog.WEBHOOK_EVENT_COLLAB_REQUEST_CREATED;
import static com.tsanet.application.setup.TestScenarioDataCatalog.WEBHOOK_EVENT_NOTE_CREATED;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 08 — Create and delete webhook subscription through connect-library facade.
 */
@Component
@Order(8)
public class WebhooksActionsScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(WebhooksActionsScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public WebhooksActionsScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "webhooks-actions";
    }

    @Override
    public void run() {
        TsaNetScenarioProperties.CompanyCredentials acme = scenarioProperties.acme();
        log.info("=== Scenario: {} ===", name());

        log.info("Step 1: login as Acme ({})", acme.username());
        TsaNetApiSession session = sessionFactory.openSession("scenario-webhooks-actions", acme.username(), acme.password());
        session.auth().login(acme.username(), acme.password());

        log.info("Step 2: cleanup pre-existing webhook with callback {}", SCENARIO_WEBHOOK_CALLBACK_URL);
        cleanupByCallback(session, SCENARIO_WEBHOOK_CALLBACK_URL);

        log.info("Step 3: create webhook subscription");
        WebhookSubscriptionResponseDto created = session.webhooks().createSubscription(
            SCENARIO_WEBHOOK_CALLBACK_URL,
            List.of(WEBHOOK_EVENT_COLLAB_REQUEST_CREATED, WEBHOOK_EVENT_NOTE_CREATED)
        );
        log.info(
            "Created webhook id={} callback={} events={} active={} secretPresent={}",
            created.id(),
            created.callbackUrl(),
            created.eventTypes(),
            created.active(),
            created.secret() != null && !created.secret().isBlank()
        );

        log.info("Step 4: verify subscription is present in list");
        List<WebhookSubscriptionDto> afterCreate = session.webhooks().listSubscriptions();
        WebhookSubscriptionDto createdInList = afterCreate.stream()
            .filter(hook -> Objects.equals(created.id(), hook.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Created webhook not visible in listSubscriptions()"));

        log.info(
            "Webhook present in list id={} callback={} active={} events={}",
            createdInList.id(),
            createdInList.callbackUrl(),
            createdInList.active(),
            createdInList.eventTypes()
        );

        log.info("Step 5: delete created webhook");
        session.webhooks().deleteSubscription(created.id());

        List<WebhookSubscriptionDto> afterDelete = session.webhooks().listSubscriptions();
        boolean stillExists = afterDelete.stream().anyMatch(hook -> Objects.equals(created.id(), hook.id()));
        if (stillExists) {
            throw new IllegalStateException("Deleted webhook is still present in listSubscriptions()");
        }

        printSummaryToConsole(created, afterDelete.size());
        log.info("Scenario {} completed", name());
    }

    private void cleanupByCallback(TsaNetApiSession session, String callbackUrl) {
        List<WebhookSubscriptionDto> existing = session.webhooks().listSubscriptions();
        for (WebhookSubscriptionDto hook : existing) {
            if (Objects.equals(callbackUrl, hook.callbackUrl())) {
                session.webhooks().deleteSubscription(hook.id());
                log.info("Deleted pre-existing webhook id={}", hook.id());
            }
        }
    }

    private void printSummaryToConsole(WebhookSubscriptionResponseDto created, int remainingCount) {
        System.out.println();
        System.out.println("=== Webhook actions scenario ===");
        System.out.printf(
            "createdWebhookId=%s callback=%s events=%s%n",
            created.id(),
            created.callbackUrl(),
            created.eventTypes()
        );
        System.out.printf("remainingWebhookCount=%s%n", remainingCount);
        System.out.println("=== end webhook actions scenario ===");
        System.out.println();
    }
}
