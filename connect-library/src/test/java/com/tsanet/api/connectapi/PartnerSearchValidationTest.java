package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartnerSearchValidationTest {
    @Test
    void itAcceptsValidKeywordSearch() {
        assertThat(PartnerSearchValidation.validateKeywordSearch("Beta").valid()).isTrue();
    }

    @Test
    void itRejectsEmptyKeywordSearch() {
        assertThat(PartnerSearchValidation.validateKeywordSearch(" ").message())
            .contains("search term");
    }

    @Test
    void itRejectsOversizedKeywordSearch() {
        String oversized = "x".repeat(PartnerSearchValidation.MAX_KEYWORD_SEARCH_LENGTH + 1);
        assertThat(PartnerSearchValidation.validateKeywordSearch(oversized).message())
            .contains("search term");
    }

    @Test
    void itAcceptsValidSemanticQuery() {
        assertThat(PartnerSearchValidation.validateSemanticQuery("partners who handle AWS").valid()).isTrue();
    }

    @Test
    void itRejectsInvalidSemanticLimit() {
        assertThat(PartnerSearchValidation.validateSemanticLimit(0).message()).contains("limit");
        assertThat(PartnerSearchValidation.validateSemanticLimit(51).message()).contains("limit");
    }
}
