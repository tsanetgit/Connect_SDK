package com.tsanet.api.connectapi;

import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

public final class HttpsAttachmentConfigValidation {
    public static final int MIN_HTTPS_PORT = 1;
    public static final int MAX_HTTPS_PORT = 65535;

    private HttpsAttachmentConfigValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validate(NormalizedHttpsAttachmentConfigDto config) {
        if (config == null) {
            return ValidationResult.invalid("HTTPS attachment configuration must not be empty.");
        }
        if (config.domain() == null || config.domain().isBlank()) {
            return ValidationResult.invalid("HTTPS attachment domain must not be empty.");
        }
        if (config.password() == null || config.password().isBlank()) {
            return ValidationResult.invalid("HTTPS attachment password must not be empty.");
        }
        if (config.expiration() == null || config.expiration().isBlank()) {
            return ValidationResult.invalid("HTTPS attachment expiration must not be empty.");
        }
        try {
            OffsetDateTime.parse(config.expiration().strip());
        } catch (DateTimeParseException ex) {
            return ValidationResult.invalid("HTTPS attachment expiration must be an ISO-8601 date-time.");
        }
        if (config.httpsPath() == null || config.httpsPath().isBlank()) {
            return ValidationResult.invalid("HTTPS attachment path must not be empty.");
        }
        if (!config.httpsPath().strip().startsWith("/")) {
            return ValidationResult.invalid("HTTPS attachment path must start with '/'.");
        }
        if (config.httpsPort() == null) {
            return ValidationResult.invalid("HTTPS attachment port must not be empty.");
        }
        if (config.httpsPort() < MIN_HTTPS_PORT || config.httpsPort() > MAX_HTTPS_PORT) {
            return ValidationResult.invalid(
                "HTTPS attachment port must be between " + MIN_HTTPS_PORT + " and " + MAX_HTTPS_PORT + "."
            );
        }
        return ValidationResult.ok();
    }
}
