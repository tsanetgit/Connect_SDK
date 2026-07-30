package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class WebhookEventsCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public WebhookEventsCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "webhook-events";
    }

    @Override
    public String description() {
        return "List inbound webhook events stored locally";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            EntityPrinter.printWebhookEvents(
                cliRunContext,
                "Inbound webhook events",
                session.webhooks().listStoredInboundEvents()
            );
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
