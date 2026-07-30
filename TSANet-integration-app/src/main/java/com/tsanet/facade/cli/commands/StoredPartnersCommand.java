package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredPartnersCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredPartnersCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-partners";
    }

    @Override
    public String description() {
        return "List partner search results stored in SQLite (--search TERM optional)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            var partners = CliArgs.search(args)
                .map(searchTerm -> session.partners().listStoredPartnersForSearchTerm(searchTerm))
                .orElseGet(() -> session.partners().listStoredPartners());
            EntityPrinter.printPartnersNumbered(cliRunContext, "Stored partners", partners);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
