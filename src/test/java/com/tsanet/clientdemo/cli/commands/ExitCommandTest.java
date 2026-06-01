package com.tsanet.clientdemo.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExitCommandTest {
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
    void itThrowsExitSignal() {
        ExitCommand command = new ExitCommand();

        assertThrows(ExitSignal.class, () -> command.execute(new String[0], new Scanner("")));
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Shutting down...");
    }
}
