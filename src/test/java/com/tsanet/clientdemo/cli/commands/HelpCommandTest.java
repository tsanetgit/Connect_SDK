package com.tsanet.clientdemo.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HelpCommandTest {
    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void itPrintsAllCommandsFromRegistry() {
        Command first = command("login", "Authenticate");
        Command second = command("session", "Show current authentication state");
        CommandRegistry registry = new CommandRegistry(List.of(first, second));

        ObjectProvider<CommandRegistry> provider = new ObjectProvider<>() {
            @Override
            public CommandRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public CommandRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public CommandRegistry getIfUnique() {
                return registry;
            }

            @Override
            public CommandRegistry getObject() {
                return registry;
            }
        };

        HelpCommand command = new HelpCommand(provider);
        command.execute(new String[0], new Scanner(""));

        String printed = output.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("Available commands:");
        assertThat(printed).contains(" - login: Authenticate");
        assertThat(printed).contains(" - session: Show current authentication state");
    }

    private Command command(String name, String description) {
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public void execute(String[] args, Scanner scanner) {
            }
        };
    }
}
