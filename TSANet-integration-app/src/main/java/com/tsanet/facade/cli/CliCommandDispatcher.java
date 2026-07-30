package com.tsanet.facade.cli;

import com.tsanet.facade.cli.commands.Command;
import com.tsanet.facade.cli.commands.CommandRegistry;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class CliCommandDispatcher {
    private final CommandRegistry commandRegistry;
    private final CliRunContext cliRunContext;

    public CliCommandDispatcher(CommandRegistry commandRegistry, CliRunContext cliRunContext) {
        this.commandRegistry = commandRegistry;
        this.cliRunContext = cliRunContext;
    }

    public void runPlain(String commandName, String... args) {
        cliRunContext.configure(true, true);
        run(commandName, args);
    }

    public void run(String commandName, String... args) {
        Command command = commandRegistry.find(commandName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown command: " + commandName));
        command.execute(args, new Scanner(""));
    }
}
