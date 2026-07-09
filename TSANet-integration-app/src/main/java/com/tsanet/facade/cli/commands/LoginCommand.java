package com.tsanet.facade.cli.commands;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.GREEN;
import static com.tsanet.facade.cli.TerminalColors.RED;
import static com.tsanet.facade.cli.TerminalColors.RESET;
import static com.tsanet.facade.cli.TerminalColors.YELLOW;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.facade.session.AccountCacheSummary;
import com.tsanet.facade.session.AccountSessionView;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class LoginCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public LoginCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Authenticate and store bearer token in session";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        if (args.length >= 2) {
            login(args[0], args[1]);
            return;
        }

        if (session.auth().isAuthorized()) {
            String username = session.auth().currentUsername().orElse("unknown");
            println(YELLOW, "Already logged in as " + username);
            return;
        }

        System.out.print("Login: ");
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        String password = scanner.nextLine();
        login(username, password);
    }

    private void login(String username, String password) {
        try {
            session.auth().login(username, password);
            if (cliRunContext.isPlainOutput()) {
                System.out.println("logged in as: " + username);
                return;
            }
            println(GREEN, "Login successful. Session ready.");
            printAccountContext();
        } catch (Exception ex) {
            println(RED, "Login failed: " + ex.getMessage());
        }
    }

    private void printAccountContext() {
        if (session instanceof AccountSessionView accountSession) {
            accountSession.activeSqlitePath().ifPresent(path -> println(BLUE, "SQLite cache: " + path));
        }
        try {
            println(BLUE, AccountCacheSummary.from(session).describe());
        } catch (Exception ignored) {
        }
    }

    private void println(String color, String message) {
        if (cliRunContext.isPlainOutput()) {
            System.out.println(message);
            return;
        }
        System.out.println(color + message + RESET);
    }
}
