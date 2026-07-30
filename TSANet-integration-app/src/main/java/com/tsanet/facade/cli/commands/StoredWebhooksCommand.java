package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredWebhooksCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredWebhooksCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-webhooks";
    }

    @Override
    public String description() {
        return "List webhook subscriptions stored in SQLite";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            EntityPrinter.printWebhooks(cliRunContext, "Stored webhook subscriptions", session.webhooks().listStoredSubscriptions());
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
