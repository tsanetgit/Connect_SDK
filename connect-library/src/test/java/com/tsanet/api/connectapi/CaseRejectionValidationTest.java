package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseRejectionValidationTest {
    @Test
    void itAcceptsValidRejectionPayload() {
        assertThat(CaseRejectionValidation.validate("Engineer", "e@example.com", "+1", "Not acceptable").valid())
            .isTrue();
    }

    @Test
    void itRejectsEmptyReason() {
        assertThat(CaseRejectionValidation.validate("Engineer", "e@example.com", null, " ").message())
            .contains("reason");
    }

    @Test
    void itRejectsOversizedReason() {
        String oversized = "x".repeat(CaseRejectionValidation.MAX_REASON_LENGTH + 1);
        assertThat(CaseRejectionValidation.validate("Engineer", "e@example.com", null, oversized).message())
            .contains("reason");
    }
}
