package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestNotesListExecutor;
import com.tsanet.facade.cli.CollaborationRequestNoteAddExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class NotesCommand implements Command {
    private final TsaNetApiSession session;
    private final CollaborationRequestNotesListExecutor notesListExecutor;
    private final CollaborationRequestNoteAddExecutor noteAddExecutor;
    private final CliRunContext cliRunContext;

    public NotesCommand(
        TsaNetApiSession session,
        CollaborationRequestNotesListExecutor notesListExecutor,
        CollaborationRequestNoteAddExecutor noteAddExecutor,
        CliRunContext cliRunContext
    ) {
        this.session = session;
        this.notesListExecutor = notesListExecutor;
        this.noteAddExecutor = noteAddExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "notes";
    }

    @Override
    public String description() {
        return "Fetch or add notes: 'notes list/add --id ID' or '--token TOKEN'; '--all' for every request";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            if (args.length > 0 && "list".equalsIgnoreCase(args[0])) {
                notesListExecutor.execute(Arrays.copyOfRange(args, 1, args.length), cliRunContext);
                return;
            }
            if (args.length > 0 && "add".equalsIgnoreCase(args[0])) {
                noteAddExecutor.execute(Arrays.copyOfRange(args, 1, args.length), scanner, cliRunContext);
                return;
            }
            if (CliArgs.token(args).isPresent() || CliArgs.requestId(args).isPresent()) {
                notesListExecutor.execute(args, cliRunContext);
                return;
            }
            if (CliArgs.hasFlag(args, "--all")) {
                List<CaseNoteDto> notes = session.caseNotes().listNotesForAllRequests();
                EntityPrinter.printNotes(cliRunContext, "Notes", notes);
                return;
            }
            throw new IllegalArgumentException(
                "Provide 'notes list/add --id ID', '--token TOKEN', or '--all'"
            );
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
