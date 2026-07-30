package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CollaborationRequestApproval {
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String RESPONSE_TYPE_APPROVAL = "APPROVAL";

    private static final Set<String> TERMINAL_STATUSES = Set.of(
        STATUS_ACCEPTED,
        "RESOLVED",
        "CANCELLED",
        "CANCELED",
        "REJECTED",
        "CLOSED"
    );

    private CollaborationRequestApproval() {
    }

    public record ValidationResult(boolean approvable, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult blocked(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateForApproval(
        CollaborationRequestStatusDto request,
        List<CaseResponseDto> existingResponses
    ) {
        if (request.status() != null && TERMINAL_STATUSES.contains(request.status().toUpperCase(Locale.ROOT))) {
            return ValidationResult.blocked(messageForTerminalStatus(request.status()));
        }
        boolean hasApprovalResponse = existingResponses.stream()
            .anyMatch(response -> RESPONSE_TYPE_APPROVAL.equals(response.type()));
        if (hasApprovalResponse) {
            return ValidationResult.blocked("Request already has an approval response and cannot be approved again.");
        }
        return ValidationResult.ok();
    }

    private static String messageForTerminalStatus(String status) {
        String normalized = status.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case STATUS_ACCEPTED -> "Request is already approved (status=ACCEPTED).";
            case "RESOLVED" -> "Request is already resolved and cannot be approved.";
            case "CANCELLED", "CANCELED" -> "Request is canceled and cannot be approved.";
            case "REJECTED" -> "Request is rejected and cannot be approved.";
            case "CLOSED" -> "Request is closed and cannot be approved.";
            default -> "Request is in status " + status + " and cannot be approved.";
        };
    }
}
