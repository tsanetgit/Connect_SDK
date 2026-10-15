package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.NoteTypeDto;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityPrinterTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CliRunContext plain = new CliRunContext();
    private PrintStream originalOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        plain.configure(false, true);
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void itPrintsEachNoteTypeAsCodeThenLabelInTheServersOrder() {
        EntityPrinter.printNoteTypes(plain, List.of(
            new NoteTypeDto("partner", "Partner"),
            new NoteTypeDto("escalation", "Escalation Update"),
            new NoteTypeDto("public", "Public")));

        assertThat(lines()).containsExactly(
            "Note types (3):",
            " - partner (Partner)",
            " - escalation (Escalation Update)",
            " - public (Public)");
    }

    @Test
    void itSaysSoWhenThereAreNoNoteTypes() {
        EntityPrinter.printNoteTypes(plain, List.of());

        assertThat(lines()).containsExactly("No note types.");
    }

    private List<String> lines() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
