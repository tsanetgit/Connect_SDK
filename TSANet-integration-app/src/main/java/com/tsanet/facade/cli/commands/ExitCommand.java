package com.tsanet.facade.cli.commands;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.RESET;

import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class ExitCommand implements Command {
    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "Stop the console application";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        System.out.println(BLUE + "Shutting down..." + RESET);
        throw new ExitSignal();
    }
}
