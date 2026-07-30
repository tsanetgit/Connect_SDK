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
public class ResponsesCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public ResponsesCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "responses";
    }

    @Override
    public String description() {
        return "Fetch case responses/comments from Connect API (--token TOKEN or --all)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            List<CaseResponseDto> responses = resolveResponses(args);
            EntityPrinter.printResponses(cliRunContext, "Case responses", responses);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }

    private List<CaseResponseDto> resolveResponses(String[] args) {
        if (CliArgs.hasFlag(args, "--all")) {
            return session.caseResponses().listResponsesForAllRequests();
        }
        return session.caseResponses().listResponsesForRequest(
            CliArgs.token(args).orElseThrow(() -> new IllegalArgumentException("Provide --token TOKEN or --all"))
        );
    }
}
