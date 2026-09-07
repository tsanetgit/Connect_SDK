package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestAttachmentDeliverExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class DeliverAttachmentCommand implements Command {
    private final CollaborationRequestAttachmentDeliverExecutor deliverExecutor;
    private final CliRunContext cliRunContext;

    public DeliverAttachmentCommand(
        CollaborationRequestAttachmentDeliverExecutor deliverExecutor,
        CliRunContext cliRunContext
    ) {
        this.deliverExecutor = deliverExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "deliver-attachment";
    }

    @Override
    public String description() {
        return "Deliver one file to the partner on the direct path, V2 (--id/--token, --file PATH, --description TEXT, --sha256)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            deliverExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
