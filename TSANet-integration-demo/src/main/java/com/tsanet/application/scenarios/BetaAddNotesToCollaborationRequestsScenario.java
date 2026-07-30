package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.DEFAULT_NOTE_PRIORITY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.betaScenarioNoteSummary;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 05 — Beta lists inbound collaboration requests and adds a note to each.
 *
 * <p><strong>Prerequisites:</strong> {@link AcmeCreateCollaborationRequestsScenario} (order 2).
 */
@Component
@Order(5)
public class BetaAddNotesToCollaborationRequestsScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(BetaAddNotesToCollaborationRequestsScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public BetaAddNotesToCollaborationRequestsScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "beta-add-notes-to-collaboration-requests";
    }

    @Override
    public void run() {
        TsaNetScenarioProperties.CompanyCredentials beta = scenarioProperties.beta();

        log.info("=== Scenario: {} ===", name());

        log.info("Step 1: login as Beta ({})", beta.username());
        TsaNetApiSession session = sessionFactory.openSession("scenario-beta-notes", beta.username(), beta.password());
        session.auth().login(beta.username(), beta.password());

        log.info("Step 2: fetch inbound collaboration requests from Acme");
        List<CollaborationRequestStatusDto> inboundRequests = session.collaborationRequests().listRequests().stream()
            .filter(request -> Objects.equals(BETA_COMPANY_ID, request.receiveCompanyId()))
            .filter(request -> Objects.equals(ACME_COMPANY_ID, request.submitCompanyId()))
            .toList();

        if (inboundRequests.isEmpty()) {
            throw new IllegalStateException("Beta sees no inbound requests from Acme. Run the Acme create scenario first.");
        }

        log.info("Step 3: add a note to each inbound request (idempotent by note summary)");
        int notesCreated = 0;
        for (CollaborationRequestStatusDto request : inboundRequests) {
            String noteSummary = betaScenarioNoteSummary(request.summary());
            List<CaseNoteDto> existingNotes = session.caseNotes().listNotesForRequest(request.token());
            boolean noteExists = existingNotes.stream().anyMatch(note -> noteSummary.equals(note.summary()));
            if (noteExists) {
                log.info("Skipping note for request id={} — summary already present: {}", request.id(), noteSummary);
                continue;
            }

            String description = "Beta added this note during integration scenario for request token " + request.token();
            log.info("Creating note on request id={} token={} summary={}", request.id(), request.token(), noteSummary);
            CaseNoteDto created = session.caseNotes().createNote(
                request.token(),
                noteSummary,
                description,
                DEFAULT_NOTE_PRIORITY
            );
            notesCreated++;
            log.info("Created note id={} status={} on request id={}", created.id(), created.status(), request.id());
        }

        log.info("Scenario {} completed — {} note(s) created, {} request(s) processed", name(), notesCreated, inboundRequests.size());
    }
}
