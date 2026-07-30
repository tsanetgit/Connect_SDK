package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestAttachmentsHttpsAnalyzeExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestAttachmentsHttpsAnalyzeExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();
        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        Map<String, Object> input = CliArgs.configFile(args)
            .map(path -> CliJsonFiles.readObjectMap(Path.of(path)))
            .orElseThrow(() -> new IllegalArgumentException("Provide --config-file PATH with JSON input to analyze"));

        try {
            NormalizedHttpsAttachmentConfigDto proposed = session.attachments().analyzeHttpsAttachmentConfig(
                request.token(),
                input
            );
            AttachmentPrinter.printHttpsConfig(
                cliRunContext,
                "Proposed HTTPS attachment configuration for request id=" + request.id(),
                proposed
            );
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }
}
