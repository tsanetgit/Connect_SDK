package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredWebhookEventsCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredWebhookEventsCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-webhook-events";
    }

    @Override
    public String description() {
        return "Alias for webhook-events";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            EntityPrinter.printWebhookEvents(
                cliRunContext,
                "Stored inbound webhook events",
                session.webhooks().listStoredInboundEvents()
            );
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
