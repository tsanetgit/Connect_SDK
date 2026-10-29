package com.tsanet.api.connectapi;

public final class CaseNoteValidation {
    public static final int MAX_SUMMARY_LENGTH = 500;
    public static final int MAX_DESCRIPTION_LENGTH = 5000;

    private CaseNoteValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validate(String summary, String description) {
        return validate(summary, description, false);
    }

    /**
     * @param encrypted whether the summary and description are ciphertext. An encrypted value is
     *     opaque and far longer than its plaintext (one JWE is over 1,200 characters even for an
     *     empty string), so only the not-empty checks apply; the length caps are for plaintext.
     */
    public static ValidationResult validate(String summary, String description, boolean encrypted) {
        if (summary == null || summary.isBlank()) {
            return ValidationResult.invalid("Note summary must not be empty.");
        }
        if (description == null || description.isBlank()) {
            return ValidationResult.invalid("Note text must not be empty.");
        }
        String trimmedSummary = summary.strip();
        String trimmedDescription = description.strip();
        if (trimmedSummary.isEmpty()) {
            return ValidationResult.invalid("Note summary must not be empty.");
        }
        if (trimmedDescription.isEmpty()) {
            return ValidationResult.invalid("Note text must not be empty.");
        }
        if (encrypted) {
            return ValidationResult.ok();
        }
        if (trimmedSummary.length() > MAX_SUMMARY_LENGTH) {
            return ValidationResult.invalid(
                "Note summary exceeds maximum length of " + MAX_SUMMARY_LENGTH + " characters."
            );
        }
        if (trimmedDescription.length() > MAX_DESCRIPTION_LENGTH) {
            return ValidationResult.invalid(
                "Note text exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters."
            );
        }
        return ValidationResult.ok();
    }
}
