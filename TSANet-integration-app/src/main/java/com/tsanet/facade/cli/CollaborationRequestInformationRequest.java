package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Locale;
import java.util.Set;

public final class CollaborationRequestInformationRequest {
    public static final String STATUS_INFORMATION = "INFORMATION";
    public static final String RESPONSE_TYPE_INFORMATION_REQUEST = "INFORMATION_REQUEST";

    private static final Set<String> PERMITTED_STATUSES = Set.of("OPEN", "ACCEPTED");
    private static final Set<String> BLOCKED_STATUSES = Set.of(
        STATUS_INFORMATION,
        "REJECTED",
        "CLOSED",
        "CANCELED",
        "CANCELLED"
    );

    private CollaborationRequestInformationRequest() {
    }

    public record ValidationResult(boolean allowed, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult blocked(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateForInformationRequest(CollaborationRequestStatusDto request) {
        if (request.status() == null || request.status().isBlank()) {
            return ValidationResult.ok();
        }
        String normalized = request.status().toUpperCase(Locale.ROOT);
        if (BLOCKED_STATUSES.contains(normalized)) {
            return ValidationResult.blocked(messageForBlockedStatus(normalized));
        }
        if (!PERMITTED_STATUSES.contains(normalized)) {
            return ValidationResult.blocked(
                "Information can only be requested on open cases (current status=" + request.status() + ")."
            );
        }
        return ValidationResult.ok();
    }

    private static String messageForBlockedStatus(String status) {
        return switch (status) {
            case STATUS_INFORMATION ->
                "Case is already awaiting an information response (status=INFORMATION).";
            case "REJECTED" -> "Rejected cases cannot receive information requests.";
            case "CLOSED" -> "Closed cases cannot receive information requests.";
            case "CANCELED", "CANCELLED" -> "Canceled cases cannot receive information requests.";
            default -> "Case is in status " + status + " and cannot receive an information request.";
        };
    }
}
