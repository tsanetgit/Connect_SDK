package com.tsanet.clientdemo.cli.commands;

import static com.tsanet.clientdemo.cli.TerminalColors.GREEN;
import static com.tsanet.clientdemo.cli.TerminalColors.RED;
import static com.tsanet.clientdemo.cli.TerminalColors.RESET;
import static com.tsanet.clientdemo.cli.TerminalColors.YELLOW;

import com.tsanet.clientdemo.connectapi.ConnectApiClient;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class LoginCommand implements Command {
    private final ConnectApiClient connectApiClient;

    public LoginCommand(ConnectApiClient connectApiClient) {
        this.connectApiClient = connectApiClient;
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
        if (connectApiClient.isAuthorized()) {
            String username = connectApiClient.currentUsername().orElse("unknown");
            System.out.println(YELLOW + "Already logged in as " + username + RESET);
            return;
        }

        System.out.print("Login: ");
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        String password = scanner.nextLine();

        try {
            connectApiClient.login(username, password);
            System.out.println(GREEN + "Login successful. Session ready." + RESET);
        } catch (Exception ex) {
            System.out.println(RED + "Login failed: " + ex.getMessage() + RESET);
        }
    }
}
