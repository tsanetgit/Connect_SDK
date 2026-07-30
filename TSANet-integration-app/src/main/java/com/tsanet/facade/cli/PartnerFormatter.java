package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;

final class PartnerFormatter {
    private PartnerFormatter() {
    }

    static String describe(PartnerSelectionDto partner) {
        StringBuilder builder = new StringBuilder();
        if (partner.label() != null && !partner.label().isBlank()) {
            builder.append(partner.label());
        } else if (partner.companyName() != null) {
            builder.append(partner.companyName());
        }
        if (partner.departmentName() != null && !partner.departmentName().isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(" / ");
            }
            builder.append(partner.departmentName());
        }
        if (builder.isEmpty()) {
            return "companyId=" + partner.companyId();
        }
        return builder.toString();
    }
}
