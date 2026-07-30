package com.tsanet.api.connectapi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AttachmentForwardValidation {
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    private AttachmentForwardValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validate(String description, List<Path> files) {
        if (description == null || description.isBlank()) {
            return ValidationResult.invalid("Attachment description must not be empty.");
        }
        String trimmedDescription = description.strip();
        if (trimmedDescription.isEmpty()) {
            return ValidationResult.invalid("Attachment description must not be empty.");
        }
        if (trimmedDescription.length() > MAX_DESCRIPTION_LENGTH) {
            return ValidationResult.invalid(
                "Attachment description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters."
            );
        }
        if (files == null || files.isEmpty()) {
            return ValidationResult.invalid("At least one attachment file is required (--file PATH).");
        }

        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            if (file == null) {
                problems.add("Attachment file path must not be null.");
                continue;
            }
            if (!Files.exists(file)) {
                problems.add("Attachment file does not exist: " + file);
                continue;
            }
            if (!Files.isRegularFile(file)) {
                problems.add("Attachment path is not a regular file: " + file);
            }
        }
        if (!problems.isEmpty()) {
            return ValidationResult.invalid(String.join(" ", problems));
        }
        return ValidationResult.ok();
    }
}
