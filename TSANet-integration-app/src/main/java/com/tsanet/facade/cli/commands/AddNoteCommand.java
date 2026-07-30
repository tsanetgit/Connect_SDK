package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestNoteAddExecutor;
import com.tsanet.facade.cli.EntityPrinter;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class AddNoteCommand implements Command {
    private final CollaborationRequestNoteAddExecutor noteAddExecutor;
    private final CliRunContext cliRunContext;

    public AddNoteCommand(
        CollaborationRequestNoteAddExecutor noteAddExecutor,
        CliRunContext cliRunContext
    ) {
        this.noteAddExecutor = noteAddExecutor;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "add-note";
    }

    @Override
    public String description() {
        return "Add a note to a collaboration request (--id ID or --token TOKEN; prompts for text if omitted)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            noteAddExecutor.execute(args, scanner, cliRunContext);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
