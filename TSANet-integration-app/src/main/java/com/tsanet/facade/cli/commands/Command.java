package com.tsanet.facade.cli.commands;

import java.util.Scanner;

public interface Command {
    String name();

    String description();

    void execute(String[] args, Scanner scanner);
}
