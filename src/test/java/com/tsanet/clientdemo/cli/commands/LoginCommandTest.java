package com.tsanet.clientdemo.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.clientdemo.connectapi.ConnectApiClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LoginCommandTest {
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
    void itCallsConnectApiClientWhenLoginSucceeds() {
        ConnectApiClient connectApiClient = Mockito.mock(ConnectApiClient.class);
        LoginCommand command = new LoginCommand(connectApiClient);

        when(connectApiClient.isAuthorized()).thenReturn(false);
        when(connectApiClient.login("demo", "secret")).thenReturn("token-123");

        String input = "demo\nsecret\n";
        Scanner scanner = new Scanner(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        command.execute(new String[0], scanner);

        verify(connectApiClient).login("demo", "secret");
        assertThat(outputAsString()).contains("Login successful. Session ready.");
    }

    @Test
    void itSkipsLoginWhenAlreadyAuthorized() {
        ConnectApiClient connectApiClient = Mockito.mock(ConnectApiClient.class);
        LoginCommand command = new LoginCommand(connectApiClient);

        when(connectApiClient.isAuthorized()).thenReturn(true);
        when(connectApiClient.currentUsername()).thenReturn(Optional.of("demo"));

        Scanner scanner = new Scanner(new ByteArrayInputStream(new byte[0]));

        command.execute(new String[0], scanner);

        verify(connectApiClient, never()).login(Mockito.anyString(), Mockito.anyString());
        assertThat(outputAsString()).contains("Already logged in as demo");
    }

    @Test
    void itPrintsFailureWhenLoginThrows() {
        ConnectApiClient connectApiClient = Mockito.mock(ConnectApiClient.class);
        LoginCommand command = new LoginCommand(connectApiClient);

        when(connectApiClient.isAuthorized()).thenReturn(false);
        when(connectApiClient.login("demo", "secret")).thenThrow(new RuntimeException("bad credentials"));

        String input = "demo\nsecret\n";
        Scanner scanner = new Scanner(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        command.execute(new String[0], scanner);

        verify(connectApiClient).login("demo", "secret");
        assertThat(outputAsString()).contains("Login failed: bad credentials");
    }

    private String outputAsString() {
        return output.toString(StandardCharsets.UTF_8);
    }
}
