package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.CaseRejectionValidation;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestRejectionExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestRejectionExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);
        List<CaseResponseDto> existingResponses = loadExistingResponses(request.token());

        CollaborationRequestRejection.ValidationResult validation =
            CollaborationRequestRejection.validateForRejection(request, existingResponses);
        if (!validation.rejectable()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        String engineerName = CliArgs.engineerName(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-name VALUE"));
        String engineerEmail = CliArgs.engineerEmail(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-email VALUE"));
        String engineerPhone = CliArgs.engineerPhone(args).orElse(null);
        String reason = resolveReason(args, scanner);

        CaseRejectionValidation.ValidationResult payloadValidation =
            CaseRejectionValidation.validate(engineerName, engineerEmail, engineerPhone, reason);
        if (!payloadValidation.valid()) {
            System.out.println(EntityPrinter.error(cliRunContext, payloadValidation.message()));
            return;
        }

        try {
            CollaborationRequestStatusDto rejected = session.caseResponses().rejectRequest(
                request.token(),
                engineerName,
                engineerEmail,
                engineerPhone,
                reason
            );

            List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(rejected.token());
            CollaborationRequestPrinter.printRejectionResult(cliRunContext, rejected, responses, reason);
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }

    private static String resolveReason(String[] args, Scanner scanner) {
        if (CliArgs.reason(args).isPresent()) {
            return CliArgs.reason(args).get();
        }
        if (scanner == null) {
            throw new IllegalArgumentException("Provide --reason VALUE");
        }
        System.out.print("Rejection reason: ");
        return scanner.nextLine();
    }

    private List<CaseResponseDto> loadExistingResponses(String caseToken) {
        List<CaseResponseDto> stored = session.caseResponses().listStoredResponsesForRequest(caseToken);
        if (!stored.isEmpty()) {
            return stored;
        }
        return session.caseResponses().listResponsesForRequest(caseToken);
    }
}
