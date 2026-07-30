package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestApprovalExecutor;
import com.tsanet.facade.cli.CollaborationRequestCloseExecutor;
import com.tsanet.facade.cli.CollaborationRequestInformationRequestExecutor;
import com.tsanet.facade.cli.CollaborationRequestInformationResponseExecutor;
import com.tsanet.facade.cli.CollaborationRequestRejectionExecutor;
import com.tsanet.facade.cli.CollaborationRequestPrinter;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestsCommand implements Command {
    private final TsaNetApiSession session;
    private final CollaborationRequestApprovalExecutor approvalExecutor;
    private final CollaborationRequestCloseExecutor closeExecutor;
    private final CollaborationRequestRejectionExecutor rejectionExecutor;
    private final CollaborationRequestInformationRequestExecutor informationRequestExecutor;
    private final CollaborationRequestInformationResponseExecutor informationResponseExecutor;
    private final CliRunContext cliRunContext;

    public CollaborationRequestsCommand(
        TsaNetApiSession session,
        CollaborationRequestApprovalExecutor approvalExecutor,
        CollaborationRequestCloseExecutor closeExecutor,
        CollaborationRequestRejectionExecutor rejectionExecutor,
        CollaborationRequestInformationRequestExecutor informationRequestExecutor,
        CollaborationRequestInformationResponseExecutor informationResponseExecutor,
        CliRunContext cliRunContext
    ) {
        this.session = session;
        this.approvalExecutor = approvalExecutor;
        this.closeExecutor = closeExecutor;
        this.rejectionExecutor = rejectionExecutor;
        this.informationRequestExecutor = informationRequestExecutor;
        this.informationResponseExecutor = informationResponseExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "requests";
    }

    @Override
    public String description() {
        return "List collaboration requests, or requests approve/close/reject/info-request/info-response subcommands";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        if (args.length > 0 && "approve".equalsIgnoreCase(args[0])) {
            try {
                approvalExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
            } catch (Exception ex) {
                System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
            }
            return;
        }

        if (args.length > 0 && "close".equalsIgnoreCase(args[0])) {
            try {
                closeExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
            } catch (Exception ex) {
                System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
            }
            return;
        }

        if (args.length > 0 && "reject".equalsIgnoreCase(args[0])) {
            try {
                rejectionExecutor.execute(Arrays.copyOfRange(args, 1, args.length), scanner, cliRunContext);
            } catch (Exception ex) {
                System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
            }
            return;
        }

        if (args.length > 0 && matchesSubcommand(args[0], "info-request", "information-request")) {
            try {
                informationRequestExecutor.execute(Arrays.copyOfRange(args, 1, args.length), scanner, cliRunContext);
            } catch (Exception ex) {
                System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
            }
            return;
        }

        if (args.length > 0 && matchesSubcommand(args[0], "info-response", "information-response")) {
            try {
                informationResponseExecutor.execute(Arrays.copyOfRange(args, 1, args.length), scanner, cliRunContext);
            } catch (Exception ex) {
                System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
            }
            return;
        }

        try {
            List<CollaborationRequestStatusDto> requests = session.collaborationRequests().listRequests();
            List<CollaborationRequestStatusDto> filtered = CliArgs.companyId(args)
                .map(companyId -> requests.stream()
                    .filter(request -> companyId.equals(request.submitCompanyId()) || companyId.equals(request.receiveCompanyId()))
                    .toList())
                .orElse(requests);

            CollaborationRequestPrinter.printList(cliRunContext, "Collaboration requests", filtered);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }

    private static boolean matchesSubcommand(String arg, String... options) {
        for (String option : options) {
            if (option.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }
}
