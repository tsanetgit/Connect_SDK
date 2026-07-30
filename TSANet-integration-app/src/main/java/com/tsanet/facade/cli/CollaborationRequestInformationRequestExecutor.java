package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.CaseInformationRequestValidation;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestInformationRequestExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestInformationRequestExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        CollaborationRequestInformationRequest.ValidationResult validation =
            CollaborationRequestInformationRequest.validateForInformationRequest(request);
        if (!validation.allowed()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        String engineerName = CliArgs.engineerName(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-name VALUE"));
        String engineerEmail = CliArgs.engineerEmail(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-email VALUE"));
        String engineerPhone = CliArgs.engineerPhone(args).orElse(null);
        String requestedInformation = resolveRequestedInformation(args, scanner);

        CaseInformationRequestValidation.ValidationResult payloadValidation =
            CaseInformationRequestValidation.validate(
                engineerName,
                engineerEmail,
                engineerPhone,
                requestedInformation
            );
        if (!payloadValidation.valid()) {
            System.out.println(EntityPrinter.error(cliRunContext, payloadValidation.message()));
            return;
        }

        try {
            CollaborationRequestStatusDto updated = session.caseResponses().submitInformationRequest(
                request.token(),
                engineerName,
                engineerEmail,
                engineerPhone,
                requestedInformation
            );

            List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(updated.token());
            CollaborationRequestPrinter.printInformationRequestResult(
                cliRunContext,
                updated,
                responses,
                requestedInformation
            );
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }

    private static String resolveRequestedInformation(String[] args, Scanner scanner) {
        if (CliArgs.requestedInformation(args).isPresent()) {
            return CliArgs.requestedInformation(args).get();
        }
        if (scanner == null) {
            throw new IllegalArgumentException("Provide --requested-information VALUE");
        }
        System.out.print("Requested information: ");
        return scanner.nextLine();
    }
}
