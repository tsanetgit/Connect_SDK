package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestCloseExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestCloseExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        CollaborationRequestClose.ValidationResult validation =
            CollaborationRequestClose.validateForClose(request);
        if (!validation.closable()) {
            System.out.println(EntityPrinter.info(cliRunContext, validation.message()));
            return;
        }

        CollaborationRequestStatusDto closed = session.caseResponses().closeRequest(request.token());
        CollaborationRequestPrinter.printCloseResult(cliRunContext, closed);
    }
}
