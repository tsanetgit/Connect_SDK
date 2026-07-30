package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class FormShowExecutor {
    private final FormResolver formResolver;

    public FormShowExecutor(FormResolver formResolver) {
        this.formResolver = formResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        formResolver.requireAuthentication();
        CollaborationRequestFormTemplateDto template = formResolver.resolve(args, scanner, cliRunContext);
        FormPrinter.printTemplate(cliRunContext, "Collaboration request form", template);
        System.out.println(EntityPrinter.info(
            cliRunContext,
            "Use create-request with the same target flags and --field fieldId=value for required custom fields."
        ));
    }
}
