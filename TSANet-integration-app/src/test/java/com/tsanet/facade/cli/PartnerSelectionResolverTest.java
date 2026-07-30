package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartnerSelectionResolverTest {
    private final PartnerSelectionResolver resolver = new PartnerSelectionResolver(null);
    private final CliRunContext cliRunContext = new CliRunContext();

    @Test
    void itAutoSelectsSingleMatch() {
        var partner = new PartnerSelectionDto("Beta", "Beta Corp", "Beta", "Support", 2L, 20L, 200L);

        PartnerSelectionDto selected = resolver.selectPartner(
            List.of(partner),
            new String[] {},
            null,
            cliRunContext
        );

        assertThat(selected).isEqualTo(partner);
    }

    @Test
    void itSelectsByPartnerIndex() {
        var first = new PartnerSelectionDto("Beta", "Beta A", "Beta", "A", 2L, 20L, 200L);
        var second = new PartnerSelectionDto("Beta", "Beta B", "Beta", "B", 3L, 30L, 300L);

        PartnerSelectionDto selected = resolver.selectPartner(
            List.of(first, second),
            new String[] {"--partner-index", "2"},
            null,
            cliRunContext
        );

        assertThat(selected).isEqualTo(second);
    }

    @Test
    void itRejectsOutOfRangePartnerIndex() {
        var first = new PartnerSelectionDto("Beta", "Beta A", "Beta", "A", 2L, 20L, 200L);
        var second = new PartnerSelectionDto("Beta", "Beta B", "Beta", "B", 3L, 30L, 300L);

        assertThatThrownBy(() -> resolver.selectPartner(
            List.of(first, second),
            new String[] {"--partner-index", "3"},
            null,
            cliRunContext
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void itRejectsEmptyPartnerList() {
        assertThatThrownBy(() -> resolver.selectPartner(List.of(), new String[] {}, null, cliRunContext))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No partners matched");
    }
}
