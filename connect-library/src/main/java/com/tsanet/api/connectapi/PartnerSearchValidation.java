package com.tsanet.api.connectapi;

public final class PartnerSearchValidation {
    public static final int MAX_KEYWORD_SEARCH_LENGTH = 100;
    public static final int MAX_SEMANTIC_QUERY_LENGTH = 500;
    public static final int MIN_SEMANTIC_LIMIT = 1;
    public static final int MAX_SEMANTIC_LIMIT = 50;
    public static final int DEFAULT_SEMANTIC_LIMIT = 10;

    private PartnerSearchValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateKeywordSearch(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return ValidationResult.invalid("Partner search term must not be empty.");
        }
        String trimmed = searchTerm.strip();
        if (trimmed.isEmpty()) {
            return ValidationResult.invalid("Partner search term must not be empty.");
        }
        if (trimmed.length() > MAX_KEYWORD_SEARCH_LENGTH) {
            return ValidationResult.invalid(
                "Partner search term exceeds maximum length of " + MAX_KEYWORD_SEARCH_LENGTH + " characters."
            );
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateSemanticQuery(String query) {
        if (query == null || query.isBlank()) {
            return ValidationResult.invalid("Semantic partner search query must not be empty.");
        }
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return ValidationResult.invalid("Semantic partner search query must not be empty.");
        }
        if (trimmed.length() > MAX_SEMANTIC_QUERY_LENGTH) {
            return ValidationResult.invalid(
                "Semantic partner search query exceeds maximum length of " + MAX_SEMANTIC_QUERY_LENGTH + " characters."
            );
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateSemanticLimit(Integer limit) {
        if (limit == null) {
            return ValidationResult.ok();
        }
        if (limit < MIN_SEMANTIC_LIMIT || limit > MAX_SEMANTIC_LIMIT) {
            return ValidationResult.invalid(
                "Semantic search limit must be between " + MIN_SEMANTIC_LIMIT + " and " + MAX_SEMANTIC_LIMIT + "."
            );
        }
        return ValidationResult.ok();
    }
}
