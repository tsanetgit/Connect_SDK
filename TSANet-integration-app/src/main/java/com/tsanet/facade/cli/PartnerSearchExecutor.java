package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class PartnerSearchExecutor {
    private final PartnerSelectionResolver partnerSelectionResolver;

    public PartnerSearchExecutor(PartnerSelectionResolver partnerSelectionResolver) {
        this.partnerSelectionResolver = partnerSelectionResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        partnerSelectionResolver.requireAuthentication();

        List<PartnerSelectionDto> partners = partnerSelectionResolver.search(args);
        if (partners.isEmpty()) {
            System.out.println(EntityPrinter.error(
                cliRunContext,
                "No partners matched the search. Try a different --search value or use --semantic for natural language search."
            ));
            return;
        }

        EntityPrinter.printPartnersNumbered(cliRunContext, "Partners", partners);
        if (CliArgs.partnerIndex(args).isPresent()) {
            PartnerSelectionDto selected = partnerSelectionResolver.selectPartner(
                partners,
                args,
                scanner,
                cliRunContext
            );
            System.out.println(EntityPrinter.info(
                cliRunContext,
                "Selected partner: "
                    + PartnerFormatter.describe(selected)
                    + " (companyId="
                    + selected.companyId()
                    + ", departmentId="
                    + selected.departmentId()
                    + ", documentId="
                    + selected.documentId()
                    + ")"
            ));
            System.out.println(EntityPrinter.info(
                cliRunContext,
                "Use create-request --search "
                    + CliArgs.search(args).orElse("")
                    + " --partner-index "
                    + CliArgs.partnerIndex(args).get()
                    + " ... to create a request for this partner."
            ));
        } else if (partners.size() > 1) {
            System.out.println(EntityPrinter.info(
                cliRunContext,
                "Use --partner-index N to select a partner, or run create-request --search ... to create a request."
            ));
        }
    }
}
