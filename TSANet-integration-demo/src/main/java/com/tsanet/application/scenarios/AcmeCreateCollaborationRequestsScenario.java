package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_ACCEPTED_REQUEST_NUMBER;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_ACCEPTED_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_OPEN_REQUEST_NUMBER;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_OPEN_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_REJECTED_REQUEST_NUMBER;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_REJECTED_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 02 — Acme creates outbound collaboration requests to Beta via connect-library.
 *
 * <p><strong>Goal:</strong> call {@code collaborationRequests().createRequest()} for three demo
 * requests and confirm they appear in Acme's list.
 *
 * <p><strong>Prerequisites:</strong> companies, users, and partnership/contact forms from startup
 * setup ({@link com.tsanet.application.setup.step.CompanyAndUserSetupStep} and
 * {@link com.tsanet.application.setup.step.PartnershipAndContactFormsSetupStep}); Connect API and
 * Connect-1 running.
 *
 * <p>Creates are skipped when a request with the same summary already exists (idempotent re-runs).
 */
@Component
@Order(2)
public class AcmeCreateCollaborationRequestsScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(AcmeCreateCollaborationRequestsScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public AcmeCreateCollaborationRequestsScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "acme-create-collaboration-requests";
    }

    @Override
    public void run() {
        TsaNetScenarioProperties.CompanyCredentials acme = scenarioProperties.acme();

        log.info("=== Scenario: {} ===", name());

        log.info("Step 1: login as Acme ({}) via connect-library", acme.username());
        TsaNetApiSession session = sessionFactory.openSession("scenario-acme-create", acme.username(), acme.password());
        session.auth().login(acme.username(), acme.password());

        log.info("Step 2: create collaboration requests to Beta (company id {}) via API", BETA_COMPANY_ID);
        List<CollaborationRequestStatusDto> requests = new ArrayList<>(session.collaborationRequests().listRequests());

        createIfAbsent(
            session,
            requests,
            ACME_OPEN_REQUEST_NUMBER,
            ACME_OPEN_REQUEST_SUMMARY,
            "Acme submitted this request and is waiting for Beta to respond."
        );
        createIfAbsent(
            session,
            requests,
            ACME_ACCEPTED_REQUEST_NUMBER,
            ACME_ACCEPTED_REQUEST_SUMMARY,
            "Beta accepted this request and work is in progress."
        );
        createIfAbsent(
            session,
            requests,
            ACME_REJECTED_REQUEST_NUMBER,
            ACME_REJECTED_REQUEST_SUMMARY,
            "Beta declined this request after review."
        );

        log.info("Step 3: list requests visible to Acme after creation");
        List<CollaborationRequestStatusDto> finalList = session.collaborationRequests().listRequests();
        log.info("Acme sees {} collaboration request(s)", finalList.size());
        for (CollaborationRequestStatusDto request : finalList) {
            log.info(
                "  id={} status={} receive={}({}) token={} summary={}",
                request.id(),
                request.status(),
                request.receiveCompanyName(),
                request.receiveCompanyId(),
                request.token(),
                request.summary()
            );
        }

        log.info("Scenario {} completed", name());
    }

    private void createIfAbsent(
        TsaNetApiSession session,
        List<CollaborationRequestStatusDto> existing,
        String caseNumber,
        String summary,
        String description
    ) {
        boolean alreadyExists = existing.stream().anyMatch(request -> summary.equals(request.summary()));
        if (alreadyExists) {
            log.info("Skipping create for case {} — summary already present: {}", caseNumber, summary);
            return;
        }

        log.info("Creating request case={} summary={}", caseNumber, summary);
        CollaborationRequestStatusDto created = session.collaborationRequests().createRequest(
            BETA_COMPANY_ID,
            caseNumber,
            summary,
            description
        );
        existing.add(created);
        log.info("Created id={} status={} token={}", created.id(), created.status(), created.token());
    }
}
