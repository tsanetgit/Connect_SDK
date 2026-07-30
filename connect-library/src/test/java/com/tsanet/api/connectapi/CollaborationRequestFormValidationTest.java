package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.FormFieldDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollaborationRequestFormValidationTest {
    @Test
    void itAcceptsWhenRequiredFieldsHaveValues() {
        var fields = List.of(
            new FormFieldDto(1L, "DETAILS", "Serial Number", "TEXT", 1, true, null, null, "SN-1"),
            new FormFieldDto(2L, "DETAILS", "Optional", "TEXT", 2, false, null, null, null)
        );

        assertThat(CollaborationRequestFormValidation.validateRequiredFields(fields).valid()).isTrue();
    }

    @Test
    void itRejectsMissingRequiredFieldValues() {
        var fields = List.of(
            new FormFieldDto(1L, "DETAILS", "Serial Number", "TEXT", 1, true, null, null, " ")
        );

        assertThat(CollaborationRequestFormValidation.validateRequiredFields(fields).message())
            .contains("Serial Number");
    }
}
