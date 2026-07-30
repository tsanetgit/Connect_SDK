package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredCurrentUserCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredCurrentUserCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-me";
    }

    @Override
    public String description() {
        return "Show current user stored in SQLite";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            var users = session.users().listStoredUsers();
            if (users.isEmpty()) {
                System.out.println(EntityPrinter.error(cliRunContext, "No stored user context."));
                return;
            }
            EntityPrinter.printUserContext(cliRunContext, "Stored current user", users.getFirst());
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
