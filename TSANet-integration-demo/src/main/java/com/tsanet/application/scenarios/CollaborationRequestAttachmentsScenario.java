package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_OPEN_REQUEST_SUMMARY;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.SCENARIO_ATTACHMENT_DESCRIPTION;
import static com.tsanet.application.setup.TestScenarioDataCatalog.SCENARIO_ATTACHMENT_FILE_NAME;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Scenario 07 — Acme forwards an attachment on an open collaboration request; Beta reads attachment config.
 *
 * <p><strong>Prerequisites:</strong> {@link AcmeCreateCollaborationRequestsScenario} (order 1).
 */
@Component
@Order(7)
public class CollaborationRequestAttachmentsScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(CollaborationRequestAttachmentsScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public CollaborationRequestAttachmentsScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "collaboration-request-attachments";
    }

    @Override
    public void run() {
        log.info("=== Scenario: {} ===", name());

        TsaNetScenarioProperties.CompanyCredentials acme = scenarioProperties.acme();
        TsaNetScenarioProperties.CompanyCredentials beta = scenarioProperties.beta();

        log.info("Step 1: login as Acme ({}) and locate open collaboration request", acme.username());
        TsaNetApiSession acmeSession = sessionFactory.openSession("scenario-acme-attachments", acme.username(), acme.password());
        acmeSession.auth().login(acme.username(), acme.password());

        CollaborationRequestStatusDto openRequest = findOpenRequest(acmeSession);
        log.info("Using request id={} token={} summary={}", openRequest.id(), openRequest.token(), openRequest.summary());

        log.info("Step 2: read attachment configuration as Acme");
        AttachmentConfigDto acmeConfig = acmeSession.attachments().getAttachmentConfig(openRequest.token());
        logAttachmentConfig("Acme", acmeConfig);

        log.info("Step 3: forward attachment file as Acme");
        Path attachmentFile = materializeAttachmentFile();
        List<AttachmentForwardResultDto> forwardResults = acmeSession.attachments().forwardAttachments(
            openRequest.token(),
            SCENARIO_ATTACHMENT_DESCRIPTION,
            List.of(attachmentFile)
        );
        logForwardResults(forwardResults);
        verifyForwardSucceeded(forwardResults);

        log.info("Step 4: login as Beta ({}) and read attachment configuration", beta.username());
        TsaNetApiSession betaSession = sessionFactory.openSession("scenario-beta-attachments", beta.username(), beta.password());
        betaSession.auth().login(beta.username(), beta.password());

        CollaborationRequestStatusDto betaView = findInboundOpenRequest(betaSession);
        AttachmentConfigDto betaConfig = betaSession.attachments().getAttachmentConfig(betaView.token());
        logAttachmentConfig("Beta", betaConfig);

        printSummaryToConsole(forwardResults, betaConfig);
        log.info("Scenario {} completed", name());
    }

    private CollaborationRequestStatusDto findOpenRequest(TsaNetApiSession session) {
        return session.collaborationRequests().listRequests().stream()
            .filter(request -> ACME_OPEN_REQUEST_SUMMARY.equals(request.summary()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Acme list does not contain '" + ACME_OPEN_REQUEST_SUMMARY + "'. Run the Acme create scenario first."
            ));
    }

    private CollaborationRequestStatusDto findInboundOpenRequest(TsaNetApiSession session) {
        return session.collaborationRequests().listRequests().stream()
            .filter(request -> Objects.equals(BETA_COMPANY_ID, request.receiveCompanyId()))
            .filter(request -> Objects.equals(ACME_COMPANY_ID, request.submitCompanyId()))
            .filter(request -> ACME_OPEN_REQUEST_SUMMARY.equals(request.summary()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Beta does not see inbound open request '" + ACME_OPEN_REQUEST_SUMMARY + "'."
            ));
    }

    private Path materializeAttachmentFile() {
        try {
            Path tempDir = Files.createTempDirectory("tsanet-scenario-attachment-");
            Path target = tempDir.resolve(SCENARIO_ATTACHMENT_FILE_NAME);
            ClassPathResource resource = new ClassPathResource("scenarios/scenario-attachment.txt");
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare scenario attachment file", exception);
        }
    }

    private void logAttachmentConfig(String actor, AttachmentConfigDto config) {
        if (config.submitter() != null) {
            log.info(
                "{} attachment config submitter companyId={} parameters={}",
                actor,
                config.submitter().companyId(),
                config.submitter().parameters()
            );
        }
        if (config.receiver() != null) {
            log.info(
                "{} attachment config receiver companyId={} parameters={}",
                actor,
                config.receiver().companyId(),
                config.receiver().parameters()
            );
        }
    }

    private void logForwardResults(List<AttachmentForwardResultDto> results) {
        for (AttachmentForwardResultDto result : results) {
            log.info(
                "Forwarded file={} submitterStatus={} receiverStatus={} completeSuccess={} partialSuccess={}",
                result.fileName(),
                result.submitterStatus(),
                result.receiverStatus(),
                result.completeSuccess(),
                result.partialSuccess()
            );
        }
    }

    private void verifyForwardSucceeded(List<AttachmentForwardResultDto> results) {
        if (results.isEmpty()) {
            throw new IllegalStateException("Attachment forward returned no results");
        }
        boolean anySuccess = results.stream()
            .anyMatch(result -> Boolean.TRUE.equals(result.completeSuccess()) || Boolean.TRUE.equals(result.partialSuccess()));
        if (!anySuccess) {
            throw new IllegalStateException("Attachment forward did not report success for any file");
        }
    }

    private void printSummaryToConsole(List<AttachmentForwardResultDto> results, AttachmentConfigDto betaConfig) {
        System.out.println();
        System.out.println("=== Collaboration request attachments ===");
        for (AttachmentForwardResultDto result : results) {
            System.out.printf(
                "file=%s  submitterStatus=%s  receiverStatus=%s  completeSuccess=%s%n",
                result.fileName(),
                result.submitterStatus(),
                result.receiverStatus(),
                result.completeSuccess()
            );
        }
        if (betaConfig.receiver() != null) {
            System.out.printf(
                "Beta receiver attachment config companyId=%s%n",
                betaConfig.receiver().companyId()
            );
        }
        System.out.println("=== end collaboration request attachments ===");
        System.out.println();
    }
}
