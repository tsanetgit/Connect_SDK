package com.tsanet.clientdemo.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tsanet.clientdemo.connectapi.ConnectApiClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionStatusCommandTest {
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
    void itPrintsLoggedInStateWhenAuthorized() {
        ConnectApiClient connectApiClient = Mockito.mock(ConnectApiClient.class);
        SessionStatusCommand command = new SessionStatusCommand(connectApiClient);
        when(connectApiClient.isAuthorized()).thenReturn(true);
        when(connectApiClient.currentUsername()).thenReturn(Optional.of("demo"));

        command.execute(new String[0], new Scanner(""));

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Logged in as: demo");
    }

    @Test
    void itPrintsNotLoggedInStateWhenUnauthorized() {
        ConnectApiClient connectApiClient = Mockito.mock(ConnectApiClient.class);
        SessionStatusCommand command = new SessionStatusCommand(connectApiClient);
        when(connectApiClient.isAuthorized()).thenReturn(false);

        command.execute(new String[0], new Scanner(""));

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Not logged in");
    }
}
