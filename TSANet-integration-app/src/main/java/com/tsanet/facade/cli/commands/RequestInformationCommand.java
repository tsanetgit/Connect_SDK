package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestInformationRequestExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class RequestInformationCommand implements Command {
    private final CollaborationRequestInformationRequestExecutor informationRequestExecutor;
    private final CliRunContext cliRunContext;

    public RequestInformationCommand(
        CollaborationRequestInformationRequestExecutor informationRequestExecutor,
        CliRunContext cliRunContext
    ) {
        this.informationRequestExecutor = informationRequestExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "request-information";
    }

    @Override
    public String description() {
        return "Request additional information on an open case (--id/--token, engineer fields, optional prompt)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            informationRequestExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
