package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.AttachmentPrinter;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.StoredAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredAttachmentsCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredAttachmentsCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-attachments";
    }

    @Override
    public String description() {
        return "List stored attachment forward results and cached configs (--id/--token optional)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            if (CliArgs.token(args).isPresent()) {
                String token = CliArgs.token(args).get();
                printForwardResults(
                    session.attachments().listStoredForwardResultsForRequest(token),
                    "Stored attachments for token=" + token
                );
                printConfigs(session.attachments().listStoredAttachmentConfigsForRequest(token));
                return;
            }
            if (CliArgs.requestId(args).isPresent()) {
                long requestId = CliArgs.requestId(args).get();
                List<StoredAttachmentForwardResultDto> results = session.attachments().listStoredForwardResults().stream()
                    .filter(result -> matchesRequestId(session, requestId, result.caseToken()))
                    .toList();
                printForwardResults(results, "Stored attachments for request id=" + requestId);
                return;
            }

            printForwardResults(session.attachments().listStoredForwardResults(), "Stored attachments");
            printConfigs(session.attachments().listStoredAttachmentConfigs());
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }

    private void printForwardResults(List<StoredAttachmentForwardResultDto> results, String title) {
        AttachmentPrinter.printStoredForwardResults(cliRunContext, title, results);
    }

    private void printConfigs(List<StoredAttachmentConfigDto> configs) {
        if (configs.isEmpty()) {
            return;
        }
        for (StoredAttachmentConfigDto stored : configs) {
            AttachmentPrinter.printAttachmentConfig(
                cliRunContext,
                "Cached attachment configuration for token=" + stored.caseToken(),
                stored.config()
            );
        }
    }

    private static boolean matchesRequestId(TsaNetApiSession session, long requestId, String caseToken) {
        return session.collaborationRequests().listStoredRequests().stream()
            .anyMatch(request -> requestId == request.id() && caseToken.equals(request.token()));
    }
}
