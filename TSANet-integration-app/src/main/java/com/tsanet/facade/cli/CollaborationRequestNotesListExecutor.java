package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestNotesListExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestNotesListExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);
        CollaborationRequestNotesTimeline.AccessResult access = CollaborationRequestNotesTimeline.validateAccess(request);
        if (!access.canFetch()) {
            System.out.println(EntityPrinter.info(cliRunContext, access.message()));
            return;
        }

        List<CaseNoteDto> notes;
        try {
            notes = session.caseNotes().listNotesForRequest(request.token());
        } catch (Exception ex) {
            System.out.println(
                EntityPrinter.info(
                    cliRunContext,
                    "Notes timeline is not available for this request yet"
                        + (request.status() != null ? " (status=" + request.status() + ")." : ".")
                )
            );
            return;
        }

        EntityPrinter.printNotesTimeline(
            cliRunContext,
            request,
            CollaborationRequestNotesTimeline.chronological(notes)
        );
    }
}
