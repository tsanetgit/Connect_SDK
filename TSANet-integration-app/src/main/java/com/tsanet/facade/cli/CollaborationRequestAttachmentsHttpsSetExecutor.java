package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.HttpsAttachmentConfigValidation;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestAttachmentsHttpsSetExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestAttachmentsHttpsSetExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();
        CollaborationRequestStatusDto request = requestResolver.resolve(args);
        NormalizedHttpsAttachmentConfigDto config = HttpsAttachmentConfigArgs.resolve(args);

        HttpsAttachmentConfigValidation.ValidationResult validation = HttpsAttachmentConfigValidation.validate(config);
        if (!validation.valid()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        try {
            AttachmentConfigDto updated = session.attachments().updateHttpsAttachmentConfig(request.token(), config);
            AttachmentPrinter.printAttachmentConfig(
                cliRunContext,
                "Updated attachment configuration for request id=" + request.id() + " token=" + request.token(),
                updated
            );
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }
}
