package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.CaseInformationResponseValidation;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestInformationResponseExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestInformationResponseExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        CollaborationRequestInformationResponse.ValidationResult validation =
            CollaborationRequestInformationResponse.validateForInformationResponse(request);
        if (!validation.allowed()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        String requestedInformation = resolveRequestedInformation(args, scanner);

        CaseInformationResponseValidation.ValidationResult payloadValidation =
            CaseInformationResponseValidation.validate(requestedInformation);
        if (!payloadValidation.valid()) {
            System.out.println(EntityPrinter.error(cliRunContext, payloadValidation.message()));
            return;
        }

        try {
            CollaborationRequestStatusDto updated = session.caseResponses().submitInformationResponse(
                request.token(),
                requestedInformation
            );

            List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(updated.token());
            CollaborationRequestPrinter.printInformationResponseResult(
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
        System.out.print("Information response: ");
        return scanner.nextLine();
    }
}
