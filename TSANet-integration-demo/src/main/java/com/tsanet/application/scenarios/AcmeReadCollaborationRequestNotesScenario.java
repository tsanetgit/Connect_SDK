package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_OPEN_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_SCENARIO_NOTE_PREFIX;
import static com.tsanet.application.setup.TestScenarioDataCatalog.betaScenarioNoteSummary;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 06 — Acme reads notes on its outbound collaboration requests.
 *
 * <p><strong>Prerequisites:</strong> {@link BetaAddNotesToCollaborationRequestsScenario} (order 3).
 */
@Component
@Order(6)
public class AcmeReadCollaborationRequestNotesScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(AcmeReadCollaborationRequestNotesScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public AcmeReadCollaborationRequestNotesScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "acme-read-collaboration-request-notes";
    }

    @Override
    public void run() {
        TsaNetScenarioProperties.CompanyCredentials acme = scenarioProperties.acme();

        log.info("=== Scenario: {} ===", name());

        log.info("Step 1: login as Acme ({})", acme.username());
        TsaNetApiSession session = sessionFactory.openSession("scenario-acme-read-notes", acme.username(), acme.password());
        session.auth().login(acme.username(), acme.password());

        log.info("Step 2: list collaboration requests visible to Acme");
        List<CollaborationRequestStatusDto> requests = session.collaborationRequests().listRequests();

        log.info("Step 3: fetch notes for each request");
        List<CaseNoteDto> allNotes = new ArrayList<>();
        for (CollaborationRequestStatusDto request : requests) {
            if (request.token() == null || request.token().isBlank()) {
                continue;
            }
            List<CaseNoteDto> notes = session.caseNotes().listNotesForRequest(request.token());
            allNotes.addAll(notes);
            log.info("Request id={} summary={} has {} note(s)", request.id(), request.summary(), notes.size());
            for (CaseNoteDto note : notes) {
                log.info(
                    "  note id={} summary={} creator={} status={}",
                    note.id(),
                    note.summary(),
                    note.creatorName(),
                    note.status()
                );
            }
        }

        printNotesToConsole(allNotes);
        verifyBetaNotesPresent(requests, allNotes);

        log.info("Scenario {} completed — Acme read {} note(s) across {} request(s)", name(), allNotes.size(), requests.size());
    }

    private void printNotesToConsole(List<CaseNoteDto> notes) {
        System.out.println();
        System.out.println("=== Acme collaboration request notes ===");
        if (notes.isEmpty()) {
            System.out.println("(no notes)");
        } else {
            for (CaseNoteDto note : notes) {
                System.out.printf(
                    "caseToken=%s  id=%s  summary=%s  creator=%s  status=%s%n",
                    note.caseToken(),
                    note.id(),
                    note.summary(),
                    note.creatorName(),
                    note.status()
                );
            }
        }
        System.out.println("=== end Acme collaboration request notes ===");
        System.out.println();
    }

    private void verifyBetaNotesPresent(List<CollaborationRequestStatusDto> requests, List<CaseNoteDto> notes) {
        CollaborationRequestStatusDto openRequest = requests.stream()
            .filter(request -> ACME_OPEN_REQUEST_SUMMARY.equals(request.summary()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Acme list does not contain '" + ACME_OPEN_REQUEST_SUMMARY + "'. Run the Acme create scenario first."
            ));

        String expectedNoteSummary = betaScenarioNoteSummary(openRequest.summary());
        boolean hasBetaNote = notes.stream()
            .anyMatch(note -> expectedNoteSummary.equals(note.summary())
                && note.summary().startsWith(BETA_SCENARIO_NOTE_PREFIX));

        if (!hasBetaNote) {
            throw new IllegalStateException(
                "Acme does not see Beta note '" + expectedNoteSummary
                    + "' on the open request. Run the Beta add-notes scenario first."
            );
        }
    }
}
