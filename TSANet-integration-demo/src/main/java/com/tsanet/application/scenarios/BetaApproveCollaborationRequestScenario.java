package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_ACCEPTED_REQUEST_NUMBER;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_ACCEPTED_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_APPROVAL_ENGINEER_EMAIL;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_APPROVAL_ENGINEER_NAME;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_APPROVAL_ENGINEER_PHONE;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_APPROVAL_NEXT_STEPS;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.CASE_RESPONSE_TYPE_APPROVAL;
import static com.tsanet.application.setup.TestScenarioDataCatalog.COLLABORATION_REQUEST_STATUS_ACCEPTED;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 03 — Beta approves the inbound collaboration request meant to be accepted.
 *
 * <p>Approval is skipped when the request is already {@code ACCEPTED} or already has an
 * {@code APPROVAL} case response.
 *
 * <p><strong>Prerequisites:</strong> {@link AcmeCreateCollaborationRequestsScenario} (order 2).
 */
@Component
@Order(3)
public class BetaApproveCollaborationRequestScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(BetaApproveCollaborationRequestScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public BetaApproveCollaborationRequestScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "beta-approve-collaboration-request";
    }

    @Override
    public void run() {
        TsaNetScenarioProperties.CompanyCredentials beta = scenarioProperties.beta();

        log.info("=== Scenario: {} ===", name());

        log.info("Step 1: login as Beta ({})", beta.username());
        TsaNetApiSession session = sessionFactory.openSession("scenario-beta-approve", beta.username(), beta.password());
        session.auth().login(beta.username(), beta.password());

        log.info("Step 2: locate inbound request to approve ({})", ACME_ACCEPTED_REQUEST_SUMMARY);
        CollaborationRequestStatusDto request = findInboundAcceptedRequest(session);

        if (isAlreadyApproved(session, request)) {
            log.info(
                "Skipping approval for request id={} — already approved (status={})",
                request.id(),
                request.status()
            );
            printSummaryToConsole(request, true);
            log.info("Scenario {} completed (no-op)", name());
            return;
        }

        log.info("Step 3: approve collaboration request token={}", request.token());
        CollaborationRequestStatusDto approved = session.caseResponses().approveRequest(
            request.token(),
            ACME_ACCEPTED_REQUEST_NUMBER,
            BETA_APPROVAL_ENGINEER_NAME,
            BETA_APPROVAL_ENGINEER_EMAIL,
            BETA_APPROVAL_ENGINEER_PHONE,
            BETA_APPROVAL_NEXT_STEPS
        );
        log.info("Approved request id={} status={}", approved.id(), approved.status());

        log.info("Step 4: verify approval response is present");
        List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(approved.token());
        boolean hasApprovalResponse = responses.stream()
            .anyMatch(response -> CASE_RESPONSE_TYPE_APPROVAL.equals(response.type()));
        if (!hasApprovalResponse) {
            throw new IllegalStateException("Approval response not found after approveRequest()");
        }
        if (!COLLABORATION_REQUEST_STATUS_ACCEPTED.equals(approved.status())) {
            throw new IllegalStateException(
                "Expected status " + COLLABORATION_REQUEST_STATUS_ACCEPTED + " but got " + approved.status()
            );
        }

        printSummaryToConsole(approved, false);
        log.info("Scenario {} completed", name());
    }

    private CollaborationRequestStatusDto findInboundAcceptedRequest(TsaNetApiSession session) {
        return session.collaborationRequests().listRequests().stream()
            .filter(request -> Objects.equals(BETA_COMPANY_ID, request.receiveCompanyId()))
            .filter(request -> Objects.equals(ACME_COMPANY_ID, request.submitCompanyId()))
            .filter(request -> ACME_ACCEPTED_REQUEST_SUMMARY.equals(request.summary()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Beta does not see inbound request '" + ACME_ACCEPTED_REQUEST_SUMMARY
                    + "'. Run the Acme create scenario first."
            ));
    }

    private boolean isAlreadyApproved(TsaNetApiSession session, CollaborationRequestStatusDto request) {
        if (COLLABORATION_REQUEST_STATUS_ACCEPTED.equals(request.status())) {
            return true;
        }
        return session.caseResponses().listResponsesForRequest(request.token()).stream()
            .anyMatch(response -> CASE_RESPONSE_TYPE_APPROVAL.equals(response.type()));
    }

    private void printSummaryToConsole(CollaborationRequestStatusDto request, boolean skipped) {
        System.out.println();
        System.out.println("=== Beta collaboration request approval ===");
        System.out.printf(
            "requestId=%s  token=%s  summary=%s  status=%s  skipped=%s%n",
            request.id(),
            request.token(),
            request.summary(),
            request.status(),
            skipped
        );
        System.out.println("=== end Beta collaboration request approval ===");
        System.out.println();
    }
}
