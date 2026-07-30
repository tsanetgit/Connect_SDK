package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseInformationRequestValidationTest {
    @Test
    void itAcceptsValidInformationRequestPayload() {
        assertThat(CaseInformationRequestValidation.validate(
            "Engineer",
            "e@example.com",
            "+1",
            "Please provide serial number"
        ).valid()).isTrue();
    }

    @Test
    void itRejectsEmptyRequestedInformation() {
        assertThat(CaseInformationRequestValidation.validate("Engineer", "e@example.com", null, " ").message())
            .contains("Requested information");
    }

    @Test
    void itRejectsOversizedRequestedInformation() {
        String oversized = "x".repeat(CaseInformationRequestValidation.MAX_REQUESTED_INFORMATION_LENGTH + 1);
        assertThat(CaseInformationRequestValidation.validate("Engineer", "e@example.com", null, oversized).message())
            .contains("Requested information");
    }
}
