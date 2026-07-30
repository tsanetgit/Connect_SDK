package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestNotesListExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class NotesListCommand implements Command {
    private final CollaborationRequestNotesListExecutor notesListExecutor;
    private final CliRunContext cliRunContext;

    public NotesListCommand(
        CollaborationRequestNotesListExecutor notesListExecutor,
        CliRunContext cliRunContext
    ) {
        this.notesListExecutor = notesListExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "notes-list";
    }

    @Override
    public String description() {
        return "Fetch and display notes timeline for a request (--id ID or --token TOKEN)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            notesListExecutor.execute(args, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
