package com.tsanet.api.connectapi;

public final class CaseRejectionValidation {
    public static final int MAX_ENGINEER_NAME_LENGTH = 191;
    public static final int MAX_ENGINEER_EMAIL_LENGTH = 191;
    public static final int MAX_ENGINEER_PHONE_LENGTH = 50;
    public static final int MAX_REASON_LENGTH = 2000;

    private CaseRejectionValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validate(
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String reason
    ) {
        if (engineerName == null || engineerName.isBlank()) {
            return ValidationResult.invalid("Engineer name must not be empty.");
        }
        if (engineerEmail == null || engineerEmail.isBlank()) {
            return ValidationResult.invalid("Engineer email must not be empty.");
        }
        if (reason == null || reason.isBlank()) {
            return ValidationResult.invalid("Rejection reason must not be empty.");
        }
        String trimmedName = engineerName.strip();
        String trimmedEmail = engineerEmail.strip();
        String trimmedReason = reason.strip();
        if (trimmedName.isEmpty()) {
            return ValidationResult.invalid("Engineer name must not be empty.");
        }
        if (trimmedEmail.isEmpty()) {
            return ValidationResult.invalid("Engineer email must not be empty.");
        }
        if (trimmedReason.isEmpty()) {
            return ValidationResult.invalid("Rejection reason must not be empty.");
        }
        if (trimmedName.length() > MAX_ENGINEER_NAME_LENGTH) {
            return ValidationResult.invalid(
                "Engineer name exceeds maximum length of " + MAX_ENGINEER_NAME_LENGTH + " characters."
            );
        }
        if (trimmedEmail.length() > MAX_ENGINEER_EMAIL_LENGTH) {
            return ValidationResult.invalid(
                "Engineer email exceeds maximum length of " + MAX_ENGINEER_EMAIL_LENGTH + " characters."
            );
        }
        if (trimmedReason.length() > MAX_REASON_LENGTH) {
            return ValidationResult.invalid(
                "Rejection reason exceeds maximum length of " + MAX_REASON_LENGTH + " characters."
            );
        }
        if (engineerPhone != null && !engineerPhone.isBlank()) {
            if (engineerPhone.strip().length() > MAX_ENGINEER_PHONE_LENGTH) {
                return ValidationResult.invalid(
                    "Engineer phone exceeds maximum length of " + MAX_ENGINEER_PHONE_LENGTH + " characters."
                );
            }
        }
        return ValidationResult.ok();
    }
}
