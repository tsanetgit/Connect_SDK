package com.tsanet.application.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "TSANET_RUN_SCENARIOS", matches = "true")
class IntegrationScenariosIT {
    @Autowired
    private CollaborationRequestFormsScenario formsScenario;

    @Autowired
    private AcmeCreateCollaborationRequestsScenario acmeCreateScenario;

    @Autowired
    private BetaApproveCollaborationRequestScenario betaApproveScenario;

    @Autowired
    private BetaListCollaborationRequestsScenario betaListScenario;

    @Autowired
    private BetaAddNotesToCollaborationRequestsScenario betaAddNotesScenario;

    @Autowired
    private AcmeReadCollaborationRequestNotesScenario acmeReadNotesScenario;

    @Autowired
    private CollaborationRequestAttachmentsScenario attachmentsScenario;

    @Autowired
    private WebhooksActionsScenario webhooksActionsScenario;

    @Test
    void itRunsCollaborationRequestAndNoteScenarios() {
        formsScenario.run();
        acmeCreateScenario.run();
        betaApproveScenario.run();
        betaListScenario.run();
        betaAddNotesScenario.run();
        acmeReadNotesScenario.run();
        attachmentsScenario.run();
        webhooksActionsScenario.run();
    }
}
