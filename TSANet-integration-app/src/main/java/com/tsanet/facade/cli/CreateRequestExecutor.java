package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CreateRequestExecutor {
    private final TsaNetApiSession session;
    private final FormResolver formResolver;

    public CreateRequestExecutor(TsaNetApiSession session, FormResolver formResolver) {
        this.session = session;
        this.formResolver = formResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        formResolver.requireAuthentication();

        CollaborationRequestFormTemplateDto template = formResolver.resolve(args, scanner, cliRunContext);
        String caseNumber = CliArgs.caseNumber(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --case-number VALUE"));
        String summary = CliArgs.summary(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --summary VALUE"));
        String description = CliArgs.description(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --description VALUE"));

        Map<Long, String> customFieldValues = FormFieldInputResolver.resolve(template, args, scanner, cliRunContext);

        try {
            CollaborationRequestStatusDto created = session.collaborationRequests().createRequest(
                template,
                caseNumber,
                summary,
                description,
                customFieldValues
            );
            CollaborationRequestPrinter.printList(cliRunContext, "Created collaboration request", List.of(created));
            System.out.println(EntityPrinter.info(
                cliRunContext,
                "Submitted with documentId=" + template.documentId()
            ));
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }
}
