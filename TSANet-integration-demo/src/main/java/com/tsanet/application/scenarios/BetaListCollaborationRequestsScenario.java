package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_OPEN_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 04 — Beta lists inbound collaboration requests from Acme.
 *
 * <p><strong>Goal:</strong> log in as Beta ({@code admin@tsanet.org} by default) and print every
 * collaboration request visible to that company to the console.
 *
 * <p><strong>Prerequisites:</strong> run {@link AcmeCreateCollaborationRequestsScenario} first
 * (automatic when {@code tsanet.scenarios.run-on-startup=true}).
 */
@Component
@Order(4)
public class BetaListCollaborationRequestsScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(BetaListCollaborationRequestsScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public BetaListCollaborationRequestsScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "beta-list-collaboration-requests";
    }

    @Override
    public void run() {
        TsaNetScenarioProperties.CompanyCredentials beta = scenarioProperties.beta();

        log.info("=== Scenario: {} ===", name());

        log.info("Step 1: login as Beta ({}) via connect-library", beta.username());
        TsaNetApiSession session = sessionFactory.openSession("scenario-beta-list", beta.username(), beta.password());
        session.auth().login(beta.username(), beta.password());

        log.info("Step 2: fetch collaboration requests addressed to Beta (company id {})", BETA_COMPANY_ID);
        List<CollaborationRequestStatusDto> requests = session.collaborationRequests().listRequests();

        printRequestsToConsole(requests);
        verifyInboundFromAcme(requests);

        log.info("Scenario {} completed — Beta sees {} request(s)", name(), requests.size());
    }

    private void printRequestsToConsole(List<CollaborationRequestStatusDto> requests) {
        System.out.println();
        System.out.println("=== Beta collaboration requests ===");
        if (requests.isEmpty()) {
            System.out.println("(no requests)");
        } else {
            for (CollaborationRequestStatusDto request : requests) {
                System.out.printf(
                    "id=%s  status=%-12s  from=%s (%s)  token=%s  summary=%s%n",
                    request.id(),
                    request.status(),
                    request.submitCompanyName(),
                    request.submitCompanyId(),
                    request.token(),
                    request.summary()
                );
            }
        }
        System.out.println("=== end Beta collaboration requests ===");
        System.out.println();
    }

    private void verifyInboundFromAcme(List<CollaborationRequestStatusDto> requests) {
        boolean hasOpenRequest = requests.stream()
            .anyMatch(request -> Objects.equals(request.summary(), ACME_OPEN_REQUEST_SUMMARY));
        if (!hasOpenRequest) {
            throw new IllegalStateException(
                "Beta list does not contain '" + ACME_OPEN_REQUEST_SUMMARY
                    + "'. Run the Acme create scenario first."
            );
        }
    }
}
