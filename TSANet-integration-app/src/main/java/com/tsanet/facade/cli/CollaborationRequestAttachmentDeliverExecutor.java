package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

/**
 * {@code deliver-attachment}: one file to the partner on the direct path (V2). Prints the
 * grant's mode and per-part progress as the upload runs, then the platform's recorded
 * outcome, in the platform's own words.
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
        String description = CliArgs.description(args).orElse(null);
        boolean sha256 = Arrays.asList(args).contains("--sha256");
        String contentType = probeContentType(file);

        System.out.println(EntityPrinter.info(cliRunContext, "Delivering " + file.getFileName()
            + " to the partner on request id=" + request.id() + " token=" + request.token()));
        try {
            AttachmentCompleteResult outcome = session.attachmentsV2().send(
                request.token(),
                file,
                contentType,
                description,
                sha256,
                progress -> System.out.println(EntityPrinter.info(cliRunContext, progress.mode() + ": "
                    + progress.partsDone() + "/" + progress.partsTotal() + " parts, "
                    + progress.bytesSent() + " of " + progress.bytesTotal() + " bytes"))
            );
            System.out.println(EntityPrinter.info(cliRunContext, "Platform outcome: " + outcome.status()
                + (outcome.verification() != null && outcome.verification().method() != null
                    ? " (verified by " + outcome.verification().method() + ")" : "")
                + (outcome.noteId() != null ? ", case note " + outcome.noteId() : "")
                + (outcome.message() != null ? " - " + outcome.message() : "")));
        } catch (AttachmentV2Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Delivery failed: " + ex.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }

    private static String probeContentType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? "application/octet-stream" : probed;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}
