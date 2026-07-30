package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestApprovalExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class ApproveRequestCommand implements Command {
    private final CollaborationRequestApprovalExecutor approvalExecutor;
    private final CliRunContext cliRunContext;

    public ApproveRequestCommand(
        CollaborationRequestApprovalExecutor approvalExecutor,
        CliRunContext cliRunContext
    ) {
        this.approvalExecutor = approvalExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "approve-request";
    }

    @Override
    public String description() {
        return "Approve an incoming collaboration request (--id ID or --token TOKEN, engineer fields required)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            approvalExecutor.execute(args, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
