package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CreateRequestExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CreateRequestCommand implements Command {
    private final CreateRequestExecutor createRequestExecutor;
    private final CliRunContext cliRunContext;

    public CreateRequestCommand(CreateRequestExecutor createRequestExecutor, CliRunContext cliRunContext) {
        this.createRequestExecutor = createRequestExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "create-request";
    }

    @Override
    public String description() {
        return "Create a collaboration request (--company-id ID or --search TERM with optional --partner-index)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            createRequestExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
