package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Locale;
import java.util.Set;

public final class CollaborationRequestNoteAdd {
    private static final Set<String> NOTE_ADD_PERMITTED_STATUSES = Set.of("OPEN", "ACCEPTED", "RESOLVED");
    private static final Set<String> NOTE_ADD_BLOCKED_STATUSES = Set.of(
        "CLOSED",
        "CANCELED",
        "CANCELLED",
        "REJECTED"
    );

    private CollaborationRequestNoteAdd() {
    }

    public record ValidationResult(boolean canAdd, String message) {
        public static ValidationResult permitted() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult blocked(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateForAdd(CollaborationRequestStatusDto request) {
        if (request.status() == null || request.status().isBlank()) {
            return ValidationResult.permitted();
        }
        String normalized = request.status().toUpperCase(Locale.ROOT);
        if (NOTE_ADD_BLOCKED_STATUSES.contains(normalized)) {
            return ValidationResult.blocked(messageForBlockedStatus(normalized, request.status()));
        }
        if (!NOTE_ADD_PERMITTED_STATUSES.contains(normalized)) {
            return ValidationResult.blocked(
                "Notes cannot be added for requests with status " + request.status() + "."
            );
        }
        return ValidationResult.permitted();
    }

    private static String messageForBlockedStatus(String normalized, String originalStatus) {
        return switch (normalized) {
            case "CLOSED" -> "Cannot add notes to a closed request (status=CLOSED).";
            case "CANCELED", "CANCELLED" -> "Cannot add notes to a canceled request (status=" + originalStatus + ").";
            case "REJECTED" -> "Cannot add notes to a rejected request (status=REJECTED).";
            default -> "Cannot add notes for requests with status " + originalStatus + ".";
        };
    }
}
