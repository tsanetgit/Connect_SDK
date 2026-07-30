package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestAttachmentsConfigExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestAttachmentsConfigExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();
        CollaborationRequestStatusDto request = requestResolver.resolve(args);
        AttachmentConfigDto config = session.attachments().getAttachmentConfig(request.token());
        AttachmentPrinter.printAttachmentConfig(
            cliRunContext,
            "Attachment configuration for request id=" + request.id() + " token=" + request.token(),
            config
        );
    }
}
