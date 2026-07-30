package com.tsanet.api.connectapi;

public final class CaseInformationResponseValidation {
    public static final int MAX_REQUESTED_INFORMATION_LENGTH = 5000;

    private CaseInformationResponseValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validate(String requestedInformation) {
        if (requestedInformation == null || requestedInformation.isBlank()) {
            return ValidationResult.invalid("Information response must not be empty.");
        }
        String trimmed = requestedInformation.strip();
        if (trimmed.isEmpty()) {
            return ValidationResult.invalid("Information response must not be empty.");
        }
        if (trimmed.length() > MAX_REQUESTED_INFORMATION_LENGTH) {
            return ValidationResult.invalid(
                "Information response exceeds maximum length of " + MAX_REQUESTED_INFORMATION_LENGTH + " characters."
            );
        }
        return ValidationResult.ok();
    }
}
