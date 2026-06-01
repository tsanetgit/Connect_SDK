package com.tsanet.clientdemo.cli.commands;

import static com.tsanet.clientdemo.cli.TerminalColors.GREEN;
import static com.tsanet.clientdemo.cli.TerminalColors.RESET;
import static com.tsanet.clientdemo.cli.TerminalColors.YELLOW;

import com.tsanet.clientdemo.connectapi.ConnectApiClient;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class SessionStatusCommand implements Command {
    private final ConnectApiClient connectApiClient;

    public SessionStatusCommand(ConnectApiClient connectApiClient) {
        this.connectApiClient = connectApiClient;
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
        if (connectApiClient.isAuthorized()) {
            String username = connectApiClient.currentUsername().orElse("unknown");
            System.out.println(GREEN + "Logged in as: " + username + RESET);
            return;
        }
        System.out.println(YELLOW + "Not logged in" + RESET);
    }
}
