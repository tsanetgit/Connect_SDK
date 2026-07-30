package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.facade.cli.FormPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredFormsCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredFormsCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-forms";
    }

    @Override
    public String description() {
        return "List cached collaboration request forms (--company-id or --document-id optional)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            if (CliArgs.documentId(args).isPresent()) {
                printAll(session.collaborationRequests().listStoredFormsForDocument(CliArgs.documentId(args).get()));
                return;
            }
            if (CliArgs.companyId(args).isPresent()) {
                printAll(session.collaborationRequests().listStoredFormsForReceiver(CliArgs.companyId(args).get()));
                return;
            }
            printAll(session.collaborationRequests().listStoredForms());
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }

    private void printAll(java.util.List<CollaborationRequestFormDto> forms) {
        if (forms.isEmpty()) {
            System.out.println(EntityPrinter.info(cliRunContext, "No stored forms."));
            return;
        }
        for (CollaborationRequestFormDto form : forms) {
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
}
