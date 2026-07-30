package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestApprovalExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestApprovalExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);
        List<CaseResponseDto> existingResponses = loadExistingResponses(request.token());

        CollaborationRequestApproval.ValidationResult validation =
            CollaborationRequestApproval.validateForApproval(request, existingResponses);
        if (!validation.approvable()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        String caseNumber = CliArgs.caseNumber(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --case-number VALUE"));
        String engineerName = CliArgs.engineerName(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-name VALUE"));
        String engineerEmail = CliArgs.engineerEmail(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-email VALUE"));
        String engineerPhone = CliArgs.engineerPhone(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --engineer-phone VALUE"));
        String nextSteps = CliArgs.nextSteps(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --next-steps VALUE"));

        CollaborationRequestStatusDto approved = session.caseResponses().approveRequest(
            request.token(),
            caseNumber,
            engineerName,
            engineerEmail,
            engineerPhone,
            nextSteps
        );

        List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(approved.token());
        CollaborationRequestPrinter.printApprovalResult(cliRunContext, approved, responses);
    }

    private List<CaseResponseDto> loadExistingResponses(String caseToken) {
        List<CaseResponseDto> stored = session.caseResponses().listStoredResponsesForRequest(caseToken);
        if (!stored.isEmpty()) {
            return stored;
        }
        return session.caseResponses().listResponsesForRequest(caseToken);
    }
}
