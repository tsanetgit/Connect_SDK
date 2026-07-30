package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredResponsesCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredResponsesCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-responses";
    }

    @Override
    public String description() {
        return "List case responses stored in SQLite (--token TOKEN optional)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            List<CaseResponseDto> responses = CliArgs.token(args)
                .map(token -> session.caseResponses().listStoredResponsesForRequest(token))
                .orElseGet(() -> session.caseResponses().listStoredResponses());
            EntityPrinter.printResponses(cliRunContext, "Stored case responses", responses);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
