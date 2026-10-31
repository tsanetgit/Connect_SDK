package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CompanyAttachmentConfigDto;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachmentPrinterTest {
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
    void itPrintsParameterNamesButNotTheirValues() {
        AttachmentPrinter.printAttachmentConfig(plain, "Attachment configuration", new AttachmentConfigDto(
            new CompanyAttachmentConfigDto(123L, Map.of("password", "sample-attachment-password")),
            new CompanyAttachmentConfigDto(456L, null)));

        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("submitter: companyId=123 parameterNames=[password]")
            .contains("receiver: companyId=456 parameterNames=[]")
            .doesNotContain("sample-attachment-password");
    }
}
