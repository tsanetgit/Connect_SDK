package com.tsanet.facade.cli.commands;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.GREEN;
import static com.tsanet.facade.cli.TerminalColors.RESET;
import static com.tsanet.facade.cli.TerminalColors.YELLOW;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.session.AccountSessionView;
import java.time.Instant;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class SessionStatusCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public SessionStatusCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "session";
    }

    @Override
    public String description() {
        return "Show current authentication state";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        if (session.auth().isAuthorized()) {
            String username = session.auth().currentUsername().orElse("unknown");
            println(GREEN, "Logged in as: " + username);
            session.auth().authMode().ifPresent(mode -> printAuthMode(mode));
            session.auth().currentAccountId().ifPresent(accountId -> printLine("Account id: " + accountId));
            session.auth().tokenExpiresAt().ifPresent(this::printTokenExpiry);
            if (session instanceof AccountSessionView accountSession) {
                accountSession.activeAccountLabel().ifPresent(label -> {
                    if (!cliRunContext.isPlainOutput()) {
                        System.out.println(BLUE + "Account cache: " + label + RESET);
                    }
                });
                accountSession.activeSqlitePath().ifPresent(path -> {
                    if (cliRunContext.isPlainOutput()) {
                        System.out.println("sqlite: " + path);
                    } else {
                        System.out.println(BLUE + "SQLite: " + path + RESET);
                    }
                });
            }
            if (cliRunContext.isPlainOutput()) {
                System.out.println("authorized: true");
                System.out.println("username: " + username);
            }
            return;
        }
        if (session instanceof AccountSessionView accountSession) {
            if (accountSession.activeAccountLabel().isPresent()) {
                String label = accountSession.activeAccountLabel().get();
                if (cliRunContext.isPlainOutput()) {
                    System.out.println("last account: " + label);
                    System.out.println("authorized: false");
                } else {
                    println(YELLOW, "Not logged in (offline cache available for " + label + ")");
                }
                return;
            }
        }
        println(YELLOW, "Not logged in");
        if (cliRunContext.isPlainOutput()) {
            System.out.println("authorized: false");
        }
    }

    private void printAuthMode(AuthMode mode) {
        if (cliRunContext.isPlainOutput()) {
            System.out.println("authMode: " + mode);
            return;
        }
        System.out.println(BLUE + "Auth mode: " + mode + RESET);
    }

    private void printTokenExpiry(Instant expiresAt) {
        if (cliRunContext.isPlainOutput()) {
            System.out.println("tokenExpiresAt: " + expiresAt);
            return;
        }
        System.out.println(BLUE + "Token expires: " + expiresAt + RESET);
    }

    private void printLine(String message) {
        if (cliRunContext.isPlainOutput()) {
            System.out.println(message);
            return;
        }
        System.out.println(BLUE + message + RESET);
    }

    private void println(String color, String message) {
        if (cliRunContext.isPlainOutput()) {
            return;
        }
        System.out.println(color + message + RESET);
    }
}
