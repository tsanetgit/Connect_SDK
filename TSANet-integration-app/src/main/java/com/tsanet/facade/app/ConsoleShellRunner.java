package com.tsanet.facade.app;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.GREEN;
import static com.tsanet.facade.cli.TerminalColors.RED;
import static com.tsanet.facade.cli.TerminalColors.RESET;
import static com.tsanet.facade.cli.TerminalColors.YELLOW;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.facade.cli.CliCommandDispatcher;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.commands.Command;
import com.tsanet.facade.cli.commands.CommandRegistry;
import com.tsanet.facade.cli.commands.ExitSignal;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.facade.config.CliProperties;
import com.tsanet.facade.config.CliProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ConsoleShellRunner implements CommandLineRunner {
    private final CommandRegistry commandRegistry;
    private final CliCommandDispatcher commandDispatcher;
    private final ApplicationUserAccountRegistry accountRegistry;
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;
    private final CliProperties cliProperties;

    public ConsoleShellRunner(
        CommandRegistry commandRegistry,
        CliCommandDispatcher commandDispatcher,
        ApplicationUserAccountRegistry accountRegistry,
        TsaNetApiSession session,
        CliRunContext cliRunContext,
        CliProperties cliProperties
    ) {
        this.commandRegistry = commandRegistry;
        this.commandDispatcher = commandDispatcher;
        this.accountRegistry = accountRegistry;
        this.session = session;
        this.cliRunContext = cliRunContext;
        this.cliProperties = cliProperties;
    }

    @Override
    public void run(String... args) {
        BatchArgs batchArgs = parseBatchArgs(args);
        if (batchArgs.enabled()) {
            cliRunContext.configure(true, batchArgs.plainOutput());
            runBatch(batchArgs.commandLine());
            return;
        }

        if (!cliProperties.enabled()) {
            return;
        }

        cliRunContext.configure(false, false);
        println(BLUE, "TSANet integration app started. Type 'help' for commands.");

        try (Scanner scanner = new Scanner(System.in)) {
            loop(scanner);
        }
    }

    private void runBatch(String commandLine) {
        tryAutoLogin();
        if (commandLine.isBlank()) {
            println(RED, "Batch mode requires a command. Example: --batch requests --company-id 1");
            System.exit(1);
        }

        String[] parts = commandLine.trim().split("\\s+");
        String commandName = parts[0];
        String[] commandArgs = Arrays.copyOfRange(parts, 1, parts.length, String[].class);

        Command command = commandRegistry.find(commandName).orElse(null);
        if (command == null) {
            println(RED, "Unknown command: " + commandName);
            System.exit(1);
        }

        commandDispatcher.run(commandName, commandArgs);
    }

    private void tryAutoLogin() {
        if (session.auth().isAuthorized()) {
            return;
        }

        if (!accountRegistry.isEmpty()) {
            try {
                session.auth().loginWithConfiguredCredentials();
                println(GREEN, "Auto login succeeded. Ready to consume commands.");
                return;
            } catch (Exception ex) {
                if (cliRunContext.isPlainOutput()) {
                    System.err.println("Auto login failed: " + ex.getMessage());
                } else {
                    println(YELLOW, "Auto login attempted with configured properties, but failed.");
                    println(RED, "Current state: UNAUTHORIZED");
                    println(BLUE, "Use 'login' command to authenticate interactively.");
                }
                return;
            }
        }

        if (!cliRunContext.isPlainOutput()) {
            println(YELLOW, "No application users configured under tsaet.accounts.");
            println(RED, "Current state: UNAUTHORIZED");
            println(BLUE, "Use 'login' command to authenticate interactively.");
        }
    }

    private void loop(Scanner scanner) {
        while (true) {
            System.out.print(BLUE + "tsa> " + RESET);
            if (!scanner.hasNextLine()) {
                System.out.println();
                break;
            }

            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");
            String commandName = parts[0];
            String[] commandArgs = Arrays.copyOfRange(parts, 1, parts.length);

            try {
                Command command = commandRegistry.find(commandName).orElse(null);
                if (command == null) {
                    println(RED, "Unknown command: " + commandName);
                    continue;
                }
                command.execute(commandArgs, scanner);
            } catch (ExitSignal ignored) {
                break;
            } catch (Exception ex) {
                println(RED, "Command failed: " + ex.getMessage());
            }
        }
    }

    private void println(String color, String message) {
        if (cliRunContext.isPlainOutput()) {
            System.out.println(message);
            return;
        }
        System.out.println(color + message + RESET);
    }

    private static BatchArgs parseBatchArgs(String[] args) {
        List<String> remaining = new ArrayList<>(Arrays.asList(args));
        boolean batchMode = false;
        boolean plainOutput = false;

        for (int i = 0; i < remaining.size(); i++) {
            String arg = remaining.get(i);
            if ("--batch".equals(arg)) {
                batchMode = true;
                remaining.remove(i);
                i--;
                continue;
            }
            if ("--plain".equals(arg)) {
                plainOutput = true;
                remaining.remove(i);
                i--;
            }
        }

        return new BatchArgs(batchMode, plainOutput, String.join(" ", remaining));
    }

    private record BatchArgs(boolean enabled, boolean plainOutput, String commandLine) {
    }
}
