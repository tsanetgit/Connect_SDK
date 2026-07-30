package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.CaseNoteValidation;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestNoteAddExecutor {
    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final Set<String> VALID_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final int AUTO_SUMMARY_MAX_LENGTH = 80;

    private final TsaNetApiSession session;
    private final CollaborationRequestResolver requestResolver;

    public CollaborationRequestNoteAddExecutor(
        TsaNetApiSession session,
        CollaborationRequestResolver requestResolver
    ) {
        this.session = session;
        this.requestResolver = requestResolver;
    }

    public void execute(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        requestResolver.requireAuthentication();

        CollaborationRequestStatusDto request = requestResolver.resolve(args);

        CollaborationRequestNoteAdd.ValidationResult access = CollaborationRequestNoteAdd.validateForAdd(request);
        if (!access.canAdd()) {
            System.out.println(EntityPrinter.error(cliRunContext, access.message()));
            return;
        }

        String description = resolveDescription(args, scanner);
        String summary = CliArgs.summary(args).orElseGet(() -> deriveSummary(description));
        String priority = resolvePriority(args);

        CaseNoteValidation.ValidationResult validation = CaseNoteValidation.validate(summary, description);
        if (!validation.valid()) {
            System.out.println(EntityPrinter.error(cliRunContext, validation.message()));
            return;
        }

        try {
            CaseNoteDto created = session.caseNotes().createNote(
                request.token(),
                summary,
                description,
                priority
            );
            System.out.println(
                EntityPrinter.info(
                    cliRunContext,
                    "Note created: id=" + created.id()
                        + " summary=" + created.summary()
                        + " priority=" + created.priority()
                )
            );

            List<CaseNoteDto> notes = session.caseNotes().listNotesForRequest(request.token());
            EntityPrinter.printNotesTimeline(
                cliRunContext,
                request,
                CollaborationRequestNotesTimeline.chronological(notes)
            );
        } catch (IllegalArgumentException ex) {
            System.out.println(EntityPrinter.error(cliRunContext, ex.getMessage()));
        }
    }

    private static String resolveDescription(String[] args, Scanner scanner) {
        if (CliArgs.description(args).isPresent()) {
            return CliArgs.description(args).get();
        }
        if (CliArgs.text(args).isPresent()) {
            return CliArgs.text(args).get();
        }
        if (scanner == null) {
            throw new IllegalArgumentException("Provide --description VALUE or --text VALUE");
        }
        System.out.print("Note text: ");
        return scanner.nextLine();
    }

    private static String resolvePriority(String[] args) {
        String priority = CliArgs.priority(args)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .orElse(DEFAULT_PRIORITY);
        if (!VALID_PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("Priority must be one of: LOW, MEDIUM, HIGH");
        }
        return priority;
    }

    static String deriveSummary(String description) {
        String normalized = description.strip();
        if (normalized.length() <= AUTO_SUMMARY_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, AUTO_SUMMARY_MAX_LENGTH - 3) + "...";
    }
}
