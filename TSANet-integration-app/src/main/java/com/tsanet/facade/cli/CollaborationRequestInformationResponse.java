package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Locale;

public final class CollaborationRequestInformationResponse {
    public static final String STATUS_INFORMATION = "INFORMATION";
    public static final String RESPONSE_TYPE_INFORMATION_RESPONSE = "INFORMATION_RESPONSE";

    private CollaborationRequestInformationResponse() {
    }

    public record ValidationResult(boolean allowed, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult blocked(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateForInformationResponse(CollaborationRequestStatusDto request) {
        if (request.status() == null || request.status().isBlank()) {
            return ValidationResult.blocked("Case status is unknown; cannot submit an information response.");
        }
        if (!STATUS_INFORMATION.equals(request.status().toUpperCase(Locale.ROOT))) {
            return ValidationResult.blocked(
                "Information response requires status INFORMATION (current status=" + request.status() + ")."
            );
        }
        return ValidationResult.ok();
    }
}
