package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsAddExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsConfigExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsHttpsAnalyzeExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsHttpsSetExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsListExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Arrays;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class AttachmentsCommand implements Command {
    private final CollaborationRequestAttachmentsListExecutor listExecutor;
    private final CollaborationRequestAttachmentsAddExecutor addExecutor;
    private final CollaborationRequestAttachmentsConfigExecutor configExecutor;
    private final CollaborationRequestAttachmentsHttpsAnalyzeExecutor httpsAnalyzeExecutor;
    private final CollaborationRequestAttachmentsHttpsSetExecutor httpsSetExecutor;
    private final CliRunContext cliRunContext;

    public AttachmentsCommand(
        CollaborationRequestAttachmentsListExecutor listExecutor,
        CollaborationRequestAttachmentsAddExecutor addExecutor,
        CollaborationRequestAttachmentsConfigExecutor configExecutor,
        CollaborationRequestAttachmentsHttpsAnalyzeExecutor httpsAnalyzeExecutor,
        CollaborationRequestAttachmentsHttpsSetExecutor httpsSetExecutor,
        CliRunContext cliRunContext
    ) {
        this.listExecutor = listExecutor;
        this.addExecutor = addExecutor;
        this.configExecutor = configExecutor;
        this.httpsAnalyzeExecutor = httpsAnalyzeExecutor;
        this.httpsSetExecutor = httpsSetExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "attachments";
    }

    @Override
    public String description() {
        return "List/add attachments or manage config: list/add/config/https-analyze/https-set";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            if (args.length > 0 && matchesSubcommand(args[0], "list")) {
                listExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && matchesSubcommand(args[0], "add")) {
                addExecutor.execute(Arrays.copyOfRange(args, 1, args.length), scanner, cliRunContext);
                return;
            }
            if (args.length > 0 && matchesSubcommand(args[0], "config")) {
                configExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && matchesSubcommand(args[0], "https-analyze", "analyze-https")) {
                httpsAnalyzeExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && matchesSubcommand(args[0], "https-set", "set-https")) {
                httpsSetExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            listExecutor.execute(args, cliRunContext);
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
