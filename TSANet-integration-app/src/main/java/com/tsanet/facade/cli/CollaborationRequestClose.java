package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Locale;
import java.util.Set;

public final class CollaborationRequestClose {
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Set<String> BLOCKED_STATUSES = Set.of(
        STATUS_CLOSED,
        "CANCELLED",
        "CANCELED",
        "REJECTED"
    );

    private CollaborationRequestClose() {
    }

    public record ValidationResult(boolean closable, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult blocked(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateForClose(CollaborationRequestStatusDto request) {
        if (request.status() == null) {
            return ValidationResult.ok();
        }
        String normalized = request.status().toUpperCase(Locale.ROOT);
        if (!BLOCKED_STATUSES.contains(normalized)) {
            return ValidationResult.ok();
        }
        return ValidationResult.blocked(messageForBlockedStatus(normalized));
    }

    private static String messageForBlockedStatus(String status) {
        return switch (status) {
            case STATUS_CLOSED -> "Request is already closed (status=CLOSED).";
            case "CANCELLED", "CANCELED" -> "Request is canceled and cannot be closed.";
            case "REJECTED" -> "Request is rejected and cannot be closed.";
            default -> "Request is in status " + status + " and cannot be closed.";
        };
    }
}
