package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.AttachmentForwardValidation;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestAttachmentsAddExecutor {
    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestAttachmentsAddExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();
        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        String description = resolveDescription(args, scanner);
        List<Path> files = CliArgs.files(args).stream().map(Path::of).toList();

        AttachmentForwardValidation.ValidationResult validation =
            AttachmentForwardValidation.validate(description, files);
        if (!validation.valid()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        try {
            List<AttachmentForwardResultDto> results = session.attachments().forwardAttachments(
                request.token(),
                description,
                files
            );
            AttachmentPrinter.printForwardResults(
                cliRunContext,
                "Forwarded attachments for request id=" + request.id() + " token=" + request.token(),
                results
            );

            List<StoredAttachmentForwardResultDto> stored =
                session.attachments().listStoredForwardResultsForRequest(request.token());
            AttachmentPrinter.printStoredForwardResults(cliRunContext, "Stored attachments", stored);
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }

    private static String resolveDescription(String[] args, Scanner scanner) {
        if (CliArgs.description(args).isPresent()) {
            return CliArgs.description(args).get();
        }
        if (scanner == null) {
            throw new IllegalArgumentException("Provide --description VALUE");
        }
        System.out.print("Attachment description: ");
        return scanner.nextLine();
    }
}
