package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsAddExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class AddAttachmentCommand implements Command {
    private final CollaborationRequestAttachmentsAddExecutor addExecutor;
    private final CliRunContext cliRunContext;

    public AddAttachmentCommand(
        CollaborationRequestAttachmentsAddExecutor addExecutor,
        CliRunContext cliRunContext
    ) {
        this.addExecutor = addExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "add-attachment";
    }

    @Override
    public String description() {
        return "Forward attachment files to a case (--id/--token, --description, --file PATH)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            addExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
