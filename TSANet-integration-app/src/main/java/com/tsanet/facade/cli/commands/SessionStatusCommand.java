package com.tsanet.facade.cli.commands;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.GREEN;
import static com.tsanet.facade.cli.TerminalColors.RESET;
import static com.tsanet.facade.cli.TerminalColors.YELLOW;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.facade.session.AccountSessionView;
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

    private void println(String color, String message) {
        if (cliRunContext.isPlainOutput()) {
            return;
        }
        System.out.println(color + message + RESET);
    }
}
