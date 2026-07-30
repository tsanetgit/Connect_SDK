package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestAttachmentsListExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestAttachmentsListExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();
        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        List<StoredAttachmentForwardResultDto> stored =
            session.attachments().listStoredForwardResultsForRequest(request.token());
        AttachmentPrinter.printStoredForwardResults(
            cliRunContext,
            "Attachments for request id=" + request.id() + " token=" + request.token(),
            stored
        );

        if (CliArgs.hasFlag(args, "--with-config")) {
            AttachmentConfigDto config = session.attachments().getAttachmentConfig(request.token());
            AttachmentPrinter.printAttachmentConfig(cliRunContext, "Attachment configuration", config);
        }
    }
}
