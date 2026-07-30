package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.facade.cli.PartnerSearchExecutor;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class PartnersCommand implements Command {
    private final PartnerSearchExecutor partnerSearchExecutor;
    private final CliRunContext cliRunContext;

    public PartnersCommand(PartnerSearchExecutor partnerSearchExecutor, CliRunContext cliRunContext) {
        this.partnerSearchExecutor = partnerSearchExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "partners";
    }

    @Override
    public String description() {
        return "Search partners (--search TERM, optional --semantic, --limit N, --partner-index N)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            partnerSearchExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
