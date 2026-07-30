package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class PartnerSelectionResolver {
    private final TsaNetApiSession session;

    public PartnerSelectionResolver(TsaNetApiSession session) {
        this.session = session;
    }

    public void requireAuthentication() {
        if (!session.auth().isAuthorized()) {
            throw new IllegalStateException("Authentication required. Use 'login' first.");
        }
    }

    public List<PartnerSelectionDto> search(String[] args) {
        String searchTerm = CliArgs.search(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --search TERM"));
        if (CliArgs.hasFlag(args, "--semantic")) {
            Integer limit = CliArgs.searchLimit(args).orElse(null);
            return session.partners().searchPartnersSemantic(searchTerm, limit);
        }
        return session.partners().searchPartners(searchTerm);
    }

    public long resolveReceiverCompanyId(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        if (CliArgs.companyId(args).isPresent()) {
            return CliArgs.companyId(args).get();
        }
        if (CliArgs.search(args).isPresent()) {
            List<PartnerSelectionDto> partners = search(args);
            PartnerSelectionDto selected = selectPartner(partners, args, scanner, cliRunContext);
            if (selected.companyId() == null) {
                throw new IllegalStateException(
                    "Selected partner has no companyId; cannot address a collaboration request."
                );
            }
            System.out.println(EntityPrinter.info(
                cliRunContext,
                "Selected partner: "
                    + PartnerFormatter.describe(selected)
                    + " (companyId="
                    + selected.companyId()
                    + ")"
            ));
            return selected.companyId();
        }
        throw new IllegalArgumentException(
            "Provide --company-id ID or --search TERM to find a partner (optional --partner-index N)."
        );
    }

    public PartnerSelectionDto selectPartner(
        List<PartnerSelectionDto> partners,
        String[] args,
        Scanner scanner,
        CliRunContext cliRunContext
    ) {
        if (partners.isEmpty()) {
            throw new IllegalStateException(
                "No partners matched the search. Try a different --search value or use --semantic for natural language search."
            );
        }
        if (partners.size() == 1) {
            PartnerSelectionDto only = partners.get(0);
            System.out.println(EntityPrinter.info(
                cliRunContext,
                "Single match selected: " + PartnerFormatter.describe(only)
            ));
            return only;
        }
        if (CliArgs.partnerIndex(args).isPresent()) {
            int index = CliArgs.partnerIndex(args).get();
            if (index < 1 || index > partners.size()) {
                throw new IllegalArgumentException(
                    "Partner index " + index + " is out of range (1-" + partners.size() + ")."
                );
            }
            return partners.get(index - 1);
        }
        if (scanner == null) {
            EntityPrinter.printPartnersNumbered(cliRunContext, "Matching partners", partners);
            throw new IllegalArgumentException(
                "Multiple partners matched; provide --partner-index N (1-" + partners.size() + ")."
            );
        }
        EntityPrinter.printPartnersNumbered(cliRunContext, "Matching partners", partners);
        return promptForPartner(partners, scanner);
    }

    private static PartnerSelectionDto promptForPartner(List<PartnerSelectionDto> partners, Scanner scanner) {
        while (true) {
            System.out.print("Select partner (1-" + partners.size() + "): ");
            String line = scanner.nextLine().strip();
            if (line.isEmpty()) {
                System.out.println("Enter a number between 1 and " + partners.size() + ".");
                continue;
            }
            try {
                int index = Integer.parseInt(line);
                if (index < 1 || index > partners.size()) {
                    System.out.println("Enter a number between 1 and " + partners.size() + ".");
                    continue;
                }
                return partners.get(index - 1);
            } catch (NumberFormatException ex) {
                System.out.println("Enter a number between 1 and " + partners.size() + ".");
            }
        }
    }
}
