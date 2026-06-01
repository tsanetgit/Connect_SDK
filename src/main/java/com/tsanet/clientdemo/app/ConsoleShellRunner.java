package com.tsanet.clientdemo.app;

import static com.tsanet.clientdemo.cli.TerminalColors.BLUE;
import static com.tsanet.clientdemo.cli.TerminalColors.GREEN;
import static com.tsanet.clientdemo.cli.TerminalColors.RED;
import static com.tsanet.clientdemo.cli.TerminalColors.RESET;
import static com.tsanet.clientdemo.cli.TerminalColors.YELLOW;

import com.tsanet.clientdemo.cli.commands.Command;
import com.tsanet.clientdemo.cli.commands.CommandRegistry;
import com.tsanet.clientdemo.cli.commands.ExitSignal;
import com.tsanet.clientdemo.config.AuthProperties;
import com.tsanet.clientdemo.connectapi.ConnectApiClient;
import java.util.Arrays;
import java.util.Scanner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ConsoleShellRunner implements CommandLineRunner {
    private final CommandRegistry commandRegistry;
    private final AuthProperties authProperties;
    private final ConnectApiClient connectApiClient;

    public ConsoleShellRunner(
        CommandRegistry commandRegistry,
        AuthProperties authProperties,
        ConnectApiClient connectApiClient
    ) {
        this.commandRegistry = commandRegistry;
        this.authProperties = authProperties;
        this.connectApiClient = connectApiClient;
    }

    @Override
    public void run(String... args) {
        System.out.println(BLUE + "TSANet Client Demo started. Type 'help' for commands." + RESET);

        try (Scanner scanner = new Scanner(System.in)) {
            tryAutoLogin();
            loop(scanner);
        }
    }

    private void tryAutoLogin() {
        if (authProperties.isConfigured()) {
            try {
                connectApiClient.login(authProperties.username(), authProperties.password());
                System.out.println(GREEN + "Auto login succeeded. Ready to consume commands." + RESET);
                return;
            } catch (Exception ex) {
                System.out.println(YELLOW + "Auto login attempted with configured properties, but failed." + RESET);
                System.out.println(RED + "Current state: UNAUTHORIZED" + RESET);
                System.out.println(BLUE + "Use 'login' command to authenticate interactively." + RESET);
                return;
            }
        }

        System.out.println(YELLOW + "Credentials are not fully configured in properties." + RESET);
        System.out.println(RED + "Current state: UNAUTHORIZED" + RESET);
        System.out.println(BLUE + "Use 'login' command to authenticate interactively." + RESET);
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
                Command command = commandRegistry.find(commandName)
                    .orElse(null);
                if (command == null) {
                    System.out.println(RED + "Unknown command: " + commandName + RESET);
                    continue;
                }
                command.execute(commandArgs, scanner);
            } catch (ExitSignal ignored) {
                break;
            } catch (Exception ex) {
                System.out.println(RED + "Command failed: " + ex.getMessage() + RESET);
            }
        }
    }
}
