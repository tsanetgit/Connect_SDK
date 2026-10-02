package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

/**
 * {@code deliver-attachment}: one file to the partner on the direct path (V2). Prints the
 * grant's mode and per-part progress as the upload runs, then the grant as the platform
 * recorded it.
 */
@Component
public class CollaborationRequestAttachmentDeliverExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestAttachmentDeliverExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();
        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        List<String> files = CliArgs.files(args);
        if (files.size() != 1) {
            System.out.println(EntityPrinter.error(cliRunContext, "Provide exactly one --file PATH (one grant delivers one file)"));
            return;
        }
        Path file = Path.of(files.get(0));
        if (!Files.isRegularFile(file)) {
            System.out.println(EntityPrinter.error(cliRunContext, "Attachment path is not a regular file: " + file));
            return;
        }
        if (CliArgs.description(args).isPresent() || List.of(args).contains("--sha256")) {
            System.out.println(EntityPrinter.info(cliRunContext,
                "Ignoring --description and --sha256: a V2 grant takes only the file's name and size"));
        }
        System.out.println(EntityPrinter.info(cliRunContext, "Delivering " + file.getFileName()
            + " to the partner on request id=" + request.id() + " token=" + request.token()));
        try {
            AttachmentGrant outcome = session.attachmentsV2().send(
                request.token(),
                file,
                progress -> System.out.println(EntityPrinter.info(cliRunContext, progress.mode().value() + ": "
                    + progress.partsDone() + "/" + progress.partsTotal() + " parts, "
                    + progress.bytesSent() + " of " + progress.bytesTotal() + " bytes"))
            );
            System.out.println(EntityPrinter.info(cliRunContext, "Platform outcome: grant " + outcome.grantId()
                + " " + outcome.status() + " (" + outcome.mode().value() + ", " + outcome.expectedSizeBytes() + " bytes)"));
        } catch (AttachmentV2Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Delivery failed (" + ex.code() + "): " + ex.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }
}
