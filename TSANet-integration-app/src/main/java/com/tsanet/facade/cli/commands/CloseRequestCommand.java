package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestCloseExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CloseRequestCommand implements Command {
    private final CollaborationRequestCloseExecutor closeExecutor;
    private final CliRunContext cliRunContext;

    public CloseRequestCommand(
        CollaborationRequestCloseExecutor closeExecutor,
        CliRunContext cliRunContext
    ) {
        this.closeExecutor = closeExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "close-request";
    }

    @Override
    public String description() {
        return "Close a collaboration request (--id ID or --token TOKEN)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            closeExecutor.execute(args, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
