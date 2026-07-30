package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliArgs;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class StoredNotesCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public StoredNotesCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "stored-notes";
    }

    @Override
    public String description() {
        return "List notes stored in SQLite (--token TOKEN optional)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        try {
            List<CaseNoteDto> notes = CliArgs.token(args)
                .map(token -> session.caseNotes().listStoredNotesForRequest(token))
                .orElseGet(() -> session.caseNotes().listStoredNotes());
            EntityPrinter.printNotes(cliRunContext, "Stored notes", notes);
        } catch (Exception ex) {
            System.out.println(EntityPrinter.error(cliRunContext, "Failed: " + ex.getMessage()));
        }
    }
}
