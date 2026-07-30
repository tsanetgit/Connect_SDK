package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestPrinter;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredCollaborationRequestsCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredCollaborationRequestsCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-requests";
    }

    @Override
    public String description() {
        return "List collaboration requests stored in the local SQLite database";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            List<CollaborationRequestStatusDto> requests = CliArgs.companyId(args)
                .map(companyId -> session.collaborationRequests().listStoredRequestsForCompany(companyId))
                .orElseGet(() -> session.collaborationRequests().listStoredRequests());

            CollaborationRequestPrinter.printList(cliRunContext, "Stored collaboration requests", requests);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
