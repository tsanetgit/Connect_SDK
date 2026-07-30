package com.tsanet.application.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestScenarioDataCatalogTest {
    @Test
    void itExposesStableIdentifiersForFutureScenarios() {
        assertThat(TestScenarioDataCatalog.ACME_COMPANY_ID).isEqualTo(1L);
        assertThat(TestScenarioDataCatalog.BETA_COMPANY_ID).isEqualTo(2L);
        assertThat(TestScenarioDataCatalog.ADMIN_USER_ID).isEqualTo(2L);
        assertThat(TestScenarioDataCatalog.API_USER_ID).isEqualTo(9587L);
        assertThat(TestScenarioDataCatalog.ACME_OPEN_REQUEST_SUMMARY).isNotBlank();
        assertThat(TestScenarioDataCatalog.betaScenarioNoteSummary(TestScenarioDataCatalog.ACME_OPEN_REQUEST_SUMMARY))
            .startsWith(TestScenarioDataCatalog.BETA_SCENARIO_NOTE_PREFIX);
        assertThat(TestScenarioDataCatalog.SCENARIO_ATTACHMENT_FILE_NAME).isNotBlank();
        assertThat(TestScenarioDataCatalog.SCENARIO_ATTACHMENT_DESCRIPTION).isNotBlank();
        assertThat(TestScenarioDataCatalog.SCENARIO_WEBHOOK_CALLBACK_URL).startsWith("https://");
        assertThat(TestScenarioDataCatalog.WEBHOOK_EVENT_COLLAB_REQUEST_CREATED).isNotBlank();
        assertThat(TestScenarioDataCatalog.COLLABORATION_REQUEST_STATUS_ACCEPTED).isEqualTo("ACCEPTED");
        assertThat(TestScenarioDataCatalog.BETA_APPROVAL_NEXT_STEPS).isNotBlank();
        assertThat(TestScenarioDataCatalog.CONTACT_FORM_CUSTOM_FIELD_COUNT).isEqualTo(3);
    }
}
