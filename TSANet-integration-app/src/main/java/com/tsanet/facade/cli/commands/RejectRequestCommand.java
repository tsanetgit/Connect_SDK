package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestRejectionExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class RejectRequestCommand implements Command {
    private final CollaborationRequestRejectionExecutor rejectionExecutor;
    private final CliRunContext cliRunContext;

    public RejectRequestCommand(
        CollaborationRequestRejectionExecutor rejectionExecutor,
        CliRunContext cliRunContext
    ) {
        this.rejectionExecutor = rejectionExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "reject-request";
    }

    @Override
    public String description() {
        return "Reject a collaboration request in INFORMATION status (--id/--token, engineer fields, optional reason prompt)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            rejectionExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
