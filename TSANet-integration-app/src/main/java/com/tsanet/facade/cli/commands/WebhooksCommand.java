package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.facade.cli.WebhookSubscriptionExecutor;
import com.tsanet.api.TsaNetApiSession;
import java.util.Arrays;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class WebhooksCommand implements Command {
    private final TsaNetApiSession session;
    private final WebhookSubscriptionExecutor webhookExecutor;
    private final CliRunContext cliRunContext;

    public WebhooksCommand(
        TsaNetApiSession session,
        WebhookSubscriptionExecutor webhookExecutor,
        CliRunContext cliRunContext
    ) {
        this.session = session;
        this.webhookExecutor = webhookExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "webhooks";
    }

    @Override
    public String description() {
        return "Manage webhooks: list, create, delete, deliveries";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            if (args.length > 0 && "create".equalsIgnoreCase(args[0])) {
                webhookExecutor.create(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && "delete".equalsIgnoreCase(args[0])) {
                webhookExecutor.delete(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && "deliveries".equalsIgnoreCase(args[0])) {
                webhookExecutor.deliveries(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && "list".equalsIgnoreCase(args[0])) {
                args = Arrays.copyOfRange(args, 1, args.length);
            }
            session.auth().loginWithConfiguredCredentials();
            EntityPrinter.printWebhooks(cliRunContext, "Webhook subscriptions", session.webhooks().listSubscriptions());
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
