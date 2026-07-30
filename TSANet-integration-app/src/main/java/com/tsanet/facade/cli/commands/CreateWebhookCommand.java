package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.facade.cli.WebhookSubscriptionExecutor;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CreateWebhookCommand implements Command {
    private final WebhookSubscriptionExecutor webhookExecutor;
    private final CliRunContext cliRunContext;

    public CreateWebhookCommand(WebhookSubscriptionExecutor webhookExecutor, CliRunContext cliRunContext) {
        this.webhookExecutor = webhookExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "create-webhook";
    }

    @Override
    public String description() {
        return "Register webhook subscription (--callback-url URL optional, --events comma-separated)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            webhookExecutor.create(args, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
