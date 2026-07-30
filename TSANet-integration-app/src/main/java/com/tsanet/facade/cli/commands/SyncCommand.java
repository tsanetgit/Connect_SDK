package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class SyncCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public SyncCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String description() {
        return "Fetch collaboration requests, notes, and responses for all requests";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            session.collaborationRequests().syncAllDetails();
            System.out.println("Sync completed.");
            EntityPrinter.printNotes(cliRunContext, "Stored notes", session.caseNotes().listStoredNotes());
            EntityPrinter.printResponses(cliRunContext, "Stored case responses", session.caseResponses().listStoredResponses());
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
