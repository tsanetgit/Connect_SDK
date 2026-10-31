package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.facade.AttachmentsFacade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The V1 route through the real controller, and the helper it shares with the V2 route. */
class UploadFileNameTest {

    private final AttachmentsFacade attachments = mock(AttachmentsFacade.class);
    private final List<Path> written = new ArrayList<>();

    private MockMvc mvc() {
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        when(session.attachments()).thenReturn(attachments);
        SessionGuard guard = mock(SessionGuard.class);
        when(guard.session()).thenReturn(session);
        return MockMvcBuilders.standaloneSetup(new AttachmentsController(guard)).setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void removeTheTempFiles() throws IOException {
        for (Path path : written) {
            if (Files.isRegularFile(path)) {
                Files.delete(path);
            }
        }
    }

    /** What the V1 route hands the SDK: checked while the temp file exists, before its cleanup. */
    private Path forwarded(String fileName) throws Exception {
        Path[] seen = new Path[1];
        when(attachments.forwardAttachments(anyString(), anyString(), any())).thenAnswer(call -> {
            List<Path> files = call.getArgument(2);
            seen[0] = files.get(0);
            written.add(seen[0]);
            assertThat(seen[0]).isRegularFile();
            assertThat(seen[0].normalize().getParent().getFileName().toString()).startsWith("tsanet-demo-attach");
            assertThat(seen[0].normalize()).isEqualTo(seen[0]);
            return List.of();
        });
        mvc().perform(multipart("/api/requests/tok/attachments")
                .file(new MockMultipartFile("files", fileName, "text/plain", new byte[] {1}))
                .param("description", "d"))
            .andExpect(status().isOk());
        assertThat(seen[0]).as("the SDK got the file").isNotNull();
        return seen[0];
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "report.txt|report.txt",
        "dir/report.txt|report.txt",
        "dir\\report.txt|report.txt",
        "C:\\dir\\report.txt|report.txt",
        "C:report.txt|report.txt",
        "../report.txt|report.txt",
        "..\\report.txt|report.txt",
        "a/b/../../../report.txt|report.txt",
        ".hidden|.hidden",
        "meeting 10:30.txt|meeting 10:30.txt",
        "..|attachment",
        ".|attachment",
        "...|attachment",
        "dir/..|attachment",
        "dir\\..|attachment",
        "/|attachment",
        "\\|attachment",
        "dir/|attachment",
    })
    void aV1UploadKeepsOnlyTheLastSegmentOfTheFilename(String fileName, String expected) throws Exception {
        assertThat(forwarded(fileName).getFileName().toString()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aV1UploadWithABlankFilenameGetsTheDefault(String fileName) throws Exception {
        assertThat(forwarded(fileName).getFileName().toString()).isEqualTo("attachment");
    }

    @org.junit.jupiter.api.Test
    void aV1UploadWithAnAbsoluteFilenameStaysInItsOwnDirectory(@TempDir Path elsewhere) throws Exception {
        String name = "kept-" + UUID.randomUUID() + ".txt";

        Path forwarded = forwarded(elsewhere.resolve(name).toString());

        assertThat(forwarded.getFileName().toString()).isEqualTo(name);
        assertThat(elsewhere.resolve(name)).doesNotExist();
    }

    @org.junit.jupiter.api.Test
    void aFilenameThatCannotBeAPathGetsTheDefault() {
        assertThat(AttachmentsController.lastSegmentOrDefault("na\0me.txt")).isEqualTo("attachment");
    }

    @org.junit.jupiter.api.Test
    void aNullFilenameGetsTheDefault() {
        assertThat(AttachmentsController.lastSegmentOrDefault(null)).isEqualTo("attachment");
    }
}
