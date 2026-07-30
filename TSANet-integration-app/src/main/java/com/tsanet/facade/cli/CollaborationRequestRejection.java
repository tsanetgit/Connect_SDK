package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Locale;

public final class CollaborationRequestRejection {
    public static final String STATUS_INFORMATION = "INFORMATION";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String RESPONSE_TYPE_REJECTION = "REJECTION";

    private CollaborationRequestRejection() {
    }

    public record ValidationResult(boolean rejectable, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult blocked(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateForRejection(
        CollaborationRequestStatusDto request,
        List<CaseResponseDto> existingResponses
    ) {
        if (request.status() != null && STATUS_REJECTED.equalsIgnoreCase(request.status())) {
            return ValidationResult.blocked("Request is already rejected (status=REJECTED).");
        }
        if (request.status() == null
            || !STATUS_INFORMATION.equals(request.status().toUpperCase(Locale.ROOT))) {
            String current = request.status() != null ? request.status() : "unknown";
            return ValidationResult.blocked(
                "Request must be in INFORMATION status to reject (current status=" + current + ")."
            );
        }
        boolean hasRejectionResponse = existingResponses.stream()
            .anyMatch(response -> RESPONSE_TYPE_REJECTION.equals(response.type()));
        if (hasRejectionResponse) {
            return ValidationResult.blocked("Request already has a rejection response and cannot be rejected again.");
        }
        return ValidationResult.ok();
    }
}
