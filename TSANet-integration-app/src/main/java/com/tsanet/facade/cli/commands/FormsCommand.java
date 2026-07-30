package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.facade.cli.FormShowExecutor;
import com.tsanet.facade.cli.FormPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import java.util.Arrays;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class FormsCommand implements Command {
    private final TsaNetApiSession session;
    private final FormShowExecutor formShowExecutor;
    private final CliRunContext cliRunContext;

    public FormsCommand(TsaNetApiSession session, FormShowExecutor formShowExecutor, CliRunContext cliRunContext) {
        this.session = session;
        this.formShowExecutor = formShowExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "forms";
    }

    @Override
    public String description() {
        return "Show or list collaboration request forms (show/list subcommands)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            if (args.length > 0 && "list".equalsIgnoreCase(args[0])) {
                listStored(Arrays.copyOfRange(args, 1, args.length));
                return;
            }
            if (args.length > 0 && "show".equalsIgnoreCase(args[0])) {
                formShowExecutor.execute(Arrays.copyOfRange(args, 1, args.length), scanner, cliRunContext);
                return;
            }
            formShowExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }

    private void listStored(String[] args) {
        if (CliArgs.documentId(args).isPresent()) {
            for (CollaborationRequestFormDto form : session.collaborationRequests()
                .listStoredFormsForDocument(CliArgs.documentId(args).get())) {
                printStored(form);
            }
            return;
        }
        if (CliArgs.companyId(args).isPresent()) {
            for (CollaborationRequestFormDto form : session.collaborationRequests()
                .listStoredFormsForReceiver(CliArgs.companyId(args).get())) {
                printStored(form);
            }
            return;
        }
        for (CollaborationRequestFormDto form : session.collaborationRequests().listStoredForms()) {
            printStored(form);
        }
    }

    private void printStored(CollaborationRequestFormDto form) {
        FormPrinter.printStoredForm(
            cliRunContext,
            "Stored collaboration request form",
            form.receiverCompanyId(),
            form.documentId(),
            form.departmentId(),
            form.fields()
        );
    }
}
