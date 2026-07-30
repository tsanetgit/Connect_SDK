package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestInformationResponseExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class RespondInformationCommand implements Command {
    private final CollaborationRequestInformationResponseExecutor informationResponseExecutor;
    private final CliRunContext cliRunContext;

    public RespondInformationCommand(
        CollaborationRequestInformationResponseExecutor informationResponseExecutor,
        CliRunContext cliRunContext
    ) {
        this.informationResponseExecutor = informationResponseExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "respond-information";
    }

    @Override
    public String description() {
        return "Respond to an information request (status INFORMATION; --id/--token, optional prompt)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            informationResponseExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
