package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.facade.cli.FormPrinter;
import com.tsanet.facade.cli.FormShowExecutor;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class FormCommand implements Command {
    private final FormShowExecutor formShowExecutor;
    private final CliRunContext cliRunContext;

    public FormCommand(FormShowExecutor formShowExecutor, CliRunContext cliRunContext) {
        this.formShowExecutor = formShowExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "form";
    }

    @Override
    public String description() {
        return "Fetch collaboration request form template (--company-id, --department-id, --document-id, or --search)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            formShowExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
