package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseInformationResponseValidationTest {
    @Test
    void itAcceptsValidInformationResponsePayload() {
        assertThat(CaseInformationResponseValidation.validate("Serial number is SN-12345").valid()).isTrue();
    }

    @Test
    void itRejectsEmptyResponse() {
        assertThat(CaseInformationResponseValidation.validate(" ").message())
            .contains("Information response");
    }

    @Test
    void itRejectsOversizedResponse() {
        String oversized = "x".repeat(CaseInformationResponseValidation.MAX_REQUESTED_INFORMATION_LENGTH + 1);
        assertThat(CaseInformationResponseValidation.validate(oversized).message())
            .contains("Information response");
    }
}
