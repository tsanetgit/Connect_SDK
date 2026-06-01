package com.tsanet.clientdemo.cli.commands;

import static com.tsanet.clientdemo.cli.TerminalColors.BLUE;
import static com.tsanet.clientdemo.cli.TerminalColors.RESET;

import java.util.Scanner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class HelpCommand implements Command {
    private final ObjectProvider<CommandRegistry> commandRegistryProvider;

    public HelpCommand(ObjectProvider<CommandRegistry> commandRegistryProvider) {
        this.commandRegistryProvider = commandRegistryProvider;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show all available commands";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        System.out.println(BLUE + "Available commands:" + RESET);
        CommandRegistry commandRegistry = commandRegistryProvider.getObject();
        commandRegistry.list().forEach(command -> System.out.printf(" - %s: %s%n", command.name(), command.description()));
    }
}
