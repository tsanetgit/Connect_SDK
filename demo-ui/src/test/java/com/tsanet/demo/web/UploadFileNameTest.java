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
import com.tsanet.api.facade.AttachmentsV2Facade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Both upload routes through the real controller, and the helpers they share. This suite runs
 * on Linux in CI, so a host whose paths take a drive prefix is covered only by the stub-resolve test.
 */
class UploadFileNameTest {

    private final AttachmentsFacade attachments = mock(AttachmentsFacade.class);
    private final AttachmentsV2Facade attachmentsV2 = mock(AttachmentsV2Facade.class);
    private final List<Path> written = new ArrayList<>();

    private MockMvc mvc() {
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        when(session.attachments()).thenReturn(attachments);
        when(session.attachmentsV2()).thenReturn(attachmentsV2);
        SessionGuard guard = mock(SessionGuard.class);
        when(guard.session()).thenReturn(session);
        return MockMvcBuilders.standaloneSetup(new AttachmentsController(guard)).setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void removeTheTempFiles() throws IOException {
        for (Path path : written) {
            Files.deleteIfExists(path); // the V2 route's delivery thread may have got there first
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

    /** What the V2 route hands the SDK on its delivery thread, checked while the temp file exists. */
    private Path delivered(String fileName) throws Exception {
        CompletableFuture<Path> seen = new CompletableFuture<>();
        when(attachmentsV2.send(anyString(), any(Path.class), any())).thenAnswer(call -> {
            Path temp = call.getArgument(1);
            written.add(temp);
            seen.complete(Files.isRegularFile(temp) && temp.normalize().equals(temp) ? temp : null);
            return null;
        });
        mvc().perform(multipart("/api/requests/tok/attachments/v2")
                .file(new MockMultipartFile("file", fileName, "text/plain", new byte[] {1})))
            .andExpect(status().isOk());
        Path temp = seen.get(5, TimeUnit.SECONDS);
        assertThat(temp).as("the SDK got a regular file").isNotNull();
        assertThat(temp.getParent().getFileName().toString()).startsWith("tsanet-demo-deliver");
        return temp;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "report.txt|report.txt",
        "dir/report.txt|report.txt",
        "dir\\report.txt|report.txt",
        "../report.txt|report.txt",
        "..\\report.txt|report.txt",
        "a/b/../../../report.txt|report.txt",
        "..|attachment",
        ".|attachment",
        "dir/..|attachment",
        "/|attachment",
        "dir/|attachment",
    })
    void aV2UploadKeepsOnlyTheLastSegmentOfTheFilename(String fileName, String expected) throws Exception {
        assertThat(delivered(fileName).getFileName().toString()).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void aV2UploadWithAnAbsoluteFilenameStaysInItsOwnDirectory(@TempDir Path elsewhere) throws Exception {
        String name = "kept-" + UUID.randomUUID() + ".txt";

        assertThat(delivered(elsewhere.resolve(name).toString()).getFileName().toString()).isEqualTo(name);
        assertThat(elsewhere.resolve(name)).doesNotExist();
    }

    /** A name this host's file system refuses, here for its length, gets the default. */
    @org.junit.jupiter.api.Test
    void aNameTheHostRefusesGetsTheDefaultOnBothRoutes() throws Exception {
        String name = "x".repeat(300) + ".txt";

        assertThat(forwarded(name).getFileName().toString()).isEqualTo("attachment");
        assertThat(delivered(name).getFileName().toString()).isEqualTo("attachment");
    }

    /** The page shows the name the upload really has. */
    @org.junit.jupiter.api.Test
    void aV2UploadReportsTheNameItKept() throws Exception {
        String name = "x".repeat(300) + ".txt";
        when(attachmentsV2.send(anyString(), any(Path.class), any())).thenReturn(null);
        MockMvc mvc = mvc();
        String answer = mvc.perform(multipart("/api/requests/tok/attachments/v2")
                .file(new MockMultipartFile("file", name, "text/plain", new byte[] {1})))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uploadId = answer.replaceAll(".*\"uploadId\":\"([^\"]+)\".*", "$1");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/uploads/" + uploadId))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.fileName").value("attachment"));
    }

    /** A part that can't be written, on either route. */
    private static MockMultipartFile unwritable(String part) {
        return new MockMultipartFile(part, "report.txt", "text/plain", new byte[] {1}) {
            @Override
            public void transferTo(Path dest) throws IOException {
                throw new IOException("full");
            }
        };
    }

    private static Set<Path> uploadDirectories() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("tsanet-demo-")
                && (path.getFileName().toString().startsWith("tsanet-demo-deliver")
                    || path.getFileName().toString().startsWith("tsanet-demo-attach"))).collect(Collectors.toSet());
        }
    }

    /** Once the app is shutting down, an upload that reaches the delivery queue is refused and cleaned up. */
    @org.junit.jupiter.api.Test
    void aRefusedDeliveryLeavesNoDirectory() throws Exception {
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        when(session.attachmentsV2()).thenReturn(attachmentsV2);
        SessionGuard guard = mock(SessionGuard.class);
        when(guard.session()).thenReturn(session);
        AttachmentsController controller = new AttachmentsController(guard);
        controller.shutdownDeliveries();
        Set<Path> before = uploadDirectories();

        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrorHandler()).build()
            .perform(multipart("/api/requests/tok/attachments/v2")
                .file(new MockMultipartFile("file", "report.txt", "text/plain", new byte[] {1})))
            .andExpect(status().isBadGateway());

        assertThat(uploadDirectories()).isSubsetOf(before);
    }

    @org.junit.jupiter.api.Test
    void aFailedWriteLeavesNoDirectoryOnTheV2Route() throws Exception {
        Set<Path> before = uploadDirectories();

        mvc().perform(multipart("/api/requests/tok/attachments/v2").file(unwritable("file")))
            .andExpect(status().isBadGateway());

        assertThat(uploadDirectories()).isSubsetOf(before);
    }

    @org.junit.jupiter.api.Test
    void aFailedWriteLeavesNoDirectoryOnTheV1RouteIncludingEarlierFiles() throws Exception {
        Set<Path> before = uploadDirectories();

        mvc().perform(multipart("/api/requests/tok/attachments")
                .file(new MockMultipartFile("files", "first.txt", "text/plain", new byte[] {1}))
                .file(unwritable("files"))
                .param("description", "d"))
            .andExpect(status().isBadGateway());

        assertThat(uploadDirectories()).isSubsetOf(before);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "report.txt|report.txt",
        "dir/report.txt|report.txt",
        "dir\\report.txt|report.txt",
        "C:\\dir\\report.txt|report.txt",
        "../report.txt|report.txt",
        "..\\report.txt|report.txt",
        "a/b/../../../report.txt|report.txt",
        ".hidden|.hidden",
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

    /** Whitespace stays as sent. Only the end of the name a host refuses would change it, and then to the default. */
    @ParameterizedTest
    @ValueSource(strings = {" lead.txt", "trail.txt ", "my report v2.pdf"})
    void aV1UploadKeepsWhitespaceAsSent(String fileName) throws Exception {
        assertThat(forwarded(fileName).getFileName().toString()).isEqualTo(fileName);
    }

    /** A colon is an ordinary character here. On a host where it starts a path root, the check below applies. */
    @ParameterizedTest
    @DisabledOnOs(OS.WINDOWS)
    @ValueSource(strings = {"Q:notes.txt", "C:report.txt", "C:D:report.txt", " C:report.txt", "meeting 10:30.txt"})
    void aV1UploadKeepsAColonWhereTheHostAllowsIt(String fileName) throws Exception {
        assertThat(forwarded(fileName).getFileName().toString()).isEqualTo(fileName);
    }

    @org.junit.jupiter.api.Test
    @DisabledOnOs(OS.WINDOWS)
    void aV1UploadKeepsAColonInTheLastSegmentAfterADirectory() throws Exception {
        assertThat(forwarded("dir/C:report.txt").getFileName().toString()).isEqualTo("C:report.txt");
    }

    /** The parent check itself, with a stub resolve, since this suite runs on one OS's path parser. */
    @org.junit.jupiter.api.Test
    void aResolvedPathOutsideTheDirectoryGetsTheDefaultName(@TempDir Path directory) {
        Path elsewhere = directory.resolve("other").resolve("report.txt");

        assertThat(AttachmentsController.uploadPath(directory, "report.txt", name -> elsewhere))
            .isEqualTo(directory.resolve("attachment"));
        assertThat(AttachmentsController.uploadPath(directory, "report.txt", name -> directory.getParent()))
            .isEqualTo(directory.resolve("attachment"));
        assertThat(AttachmentsController.uploadPath(directory, "report.txt", name -> directory.resolve("a").resolve("..")))
            .isEqualTo(directory.resolve("attachment"));
        assertThat(AttachmentsController.uploadPath(directory, "report.txt", directory::resolve))
            .isEqualTo(directory.resolve("report.txt"));
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
