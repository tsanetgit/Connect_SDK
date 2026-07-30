package com.tsanet.application.scenarios;

import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_CONTACT_DOCUMENT_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_CONTACT_DOCUMENT_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.CONTACT_FORM_CUSTOM_FIELD_COUNT;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import com.tsanet.application.config.TsaNetScenarioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Scenario 01 — Fetch collaboration request forms for partner companies via connect-library.
 *
 * <p><strong>Goal:</strong> call {@code collaborationRequests().getCreateForm()} as Acme (for Beta)
 * and as Beta (for Acme), verifying seeded contact forms from startup setup.
 *
 * <p><strong>Prerequisites:</strong> {@link com.tsanet.application.setup.step.PartnershipAndContactFormsSetupStep}.
 */
@Component
@Order(1)
public class CollaborationRequestFormsScenario implements IntegrationScenario {
    private static final Logger log = LoggerFactory.getLogger(CollaborationRequestFormsScenario.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetScenarioProperties scenarioProperties;

    public CollaborationRequestFormsScenario(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetScenarioProperties scenarioProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.scenarioProperties = scenarioProperties;
    }

    @Override
    public String name() {
        return "collaboration-request-forms";
    }

    @Override
    public void run() {
        log.info("=== Scenario: {} ===", name());

        TsaNetScenarioProperties.CompanyCredentials acme = scenarioProperties.acme();
        TsaNetScenarioProperties.CompanyCredentials beta = scenarioProperties.beta();

        log.info("Step 1: login as Acme ({}) and fetch form for Beta (company id {})", acme.username(), BETA_COMPANY_ID);
        TsaNetApiSession acmeSession = sessionFactory.openSession("scenario-acme-forms", acme.username(), acme.password());
        acmeSession.auth().login(acme.username(), acme.password());
        CollaborationRequestFormDto acmeViewOfBeta = acmeSession.collaborationRequests().getCreateForm(BETA_COMPANY_ID);
        verifyForm("Acme -> Beta", acmeViewOfBeta, BETA_COMPANY_ID, BETA_CONTACT_DOCUMENT_ID);

        log.info("Step 2: login as Beta ({}) and fetch form for Acme (company id {})", beta.username(), ACME_COMPANY_ID);
        TsaNetApiSession betaSession = sessionFactory.openSession("scenario-beta-forms", beta.username(), beta.password());
        betaSession.auth().login(beta.username(), beta.password());
        CollaborationRequestFormDto betaViewOfAcme = betaSession.collaborationRequests().getCreateForm(ACME_COMPANY_ID);
        verifyForm("Beta -> Acme", betaViewOfAcme, ACME_COMPANY_ID, ACME_CONTACT_DOCUMENT_ID);

        printSummaryToConsole(acmeViewOfBeta, betaViewOfAcme);
        log.info("Scenario {} completed", name());
    }

    private void verifyForm(
        String label,
        CollaborationRequestFormDto form,
        long expectedReceiverCompanyId,
        long expectedDocumentId
    ) {
        if (form.receiverCompanyId() != expectedReceiverCompanyId) {
            throw new IllegalStateException(
                label + " form receiverCompanyId expected " + expectedReceiverCompanyId
                    + " but got " + form.receiverCompanyId()
            );
        }
        if (form.documentId() != expectedDocumentId) {
            throw new IllegalStateException(
                label + " form documentId expected " + expectedDocumentId + " but got " + form.documentId()
            );
        }
        if (form.customFieldCount() < CONTACT_FORM_CUSTOM_FIELD_COUNT) {
            throw new IllegalStateException(
                label + " form customFieldCount expected at least " + CONTACT_FORM_CUSTOM_FIELD_COUNT
                    + " but got " + form.customFieldCount()
            );
        }

        log.info(
            "{} form: receiverCompanyId={} documentId={} customFieldCount={}",
            label,
            form.receiverCompanyId(),
            form.documentId(),
            form.customFieldCount()
        );
    }

    private void printSummaryToConsole(CollaborationRequestFormDto acmeViewOfBeta, CollaborationRequestFormDto betaViewOfAcme) {
        System.out.println();
        System.out.println("=== Collaboration request forms ===");
        System.out.printf(
            "Acme->Beta: receiverCompanyId=%s documentId=%s customFieldCount=%s%n",
            acmeViewOfBeta.receiverCompanyId(),
            acmeViewOfBeta.documentId(),
            acmeViewOfBeta.customFieldCount()
        );
        System.out.printf(
            "Beta->Acme: receiverCompanyId=%s documentId=%s customFieldCount=%s%n",
            betaViewOfAcme.receiverCompanyId(),
            betaViewOfAcme.documentId(),
            betaViewOfAcme.customFieldCount()
        );
        System.out.println("=== end collaboration request forms ===");
        System.out.println();
    }
}
