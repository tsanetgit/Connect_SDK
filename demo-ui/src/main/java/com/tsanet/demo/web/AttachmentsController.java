package com.tsanet.demo.web;

import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadProgress;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.facade.AttachmentsV2Facade;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AttachmentsController {

    private static final String DEFAULT_NAME = "attachment";

    private final SessionGuard guard;
    /** In-flight and finished V2 deliveries, keyed by upload id; demo-side state only. */
    private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();
    private final ExecutorService deliveries = Executors.newVirtualThreadPerTaskExecutor();

    public AttachmentsController(SessionGuard guard) {
        this.guard = guard;
    }

    /**
     * What the page polls: phase, the grant's mode, parts done of total, bytes sent of total,
     * and once finished the platform's recorded grant or the failure message. The grant is
     * the facade's return value verbatim; the demo never synthesizes a status.
     */
    public record UploadState(
        String uploadId,
        String fileName,
        String phase,
        String mode,
        int partsDone,
        int partsTotal,
        long bytesSent,
        long bytesTotal,
        AttachmentGrant outcome,
        String error
    ) {
        UploadState progressed(UploadProgress p) {
            return new UploadState(uploadId, fileName, "uploading", p.mode().value(), p.partsDone(), p.partsTotal(),
                p.bytesSent(), p.bytesTotal(), null, null);
        }

        UploadState finished(AttachmentGrant result) {
            return new UploadState(uploadId, fileName, "done", mode, partsDone, partsTotal, bytesSent, bytesTotal, result, null);
        }

        UploadState failed(String message) {
            return new UploadState(uploadId, fileName, "failed", mode, partsDone, partsTotal, bytesSent, bytesTotal, null, message);
        }
    }

    /**
     * Direct delivery (V2): the file goes from this demo straight into the partner's store
     * through links the platform signs, and the platform checks the upload when it completes
     * the grant. Runs on a background thread because a large file takes a while; the page
     * polls {@link #uploadState}.
     */
    @PostMapping("/api/requests/{token}/attachments/v2")
    public Map<String, String> deliver(
        @PathVariable String token,
        @RequestParam("file") MultipartFile file
    ) {
        // Resolve the facade here, on the request thread: an account-scoped session picks its
        // delegate per call, and a delivery must not follow an account switch made mid-flight.
        AttachmentsV2Facade attachments = guard.session().attachmentsV2();
        Path temp;
        try {
            temp = store("tsanet-demo-deliver", file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String original = temp.getFileName().toString();
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new UploadState(uploadId, original, "starting", null, 0, 0, 0, file.getSize(), null, null));
        try {
            deliveries.submit(() -> {
                try {
                    AttachmentGrant outcome = attachments.send(token, temp,
                        progress -> uploads.computeIfPresent(uploadId, (k, v) -> v.progressed(progress)));
                    uploads.computeIfPresent(uploadId, (k, v) -> v.finished(outcome));
                } catch (RuntimeException e) {
                    String failure = e instanceof AttachmentV2Exception v2 ? v2.code() + ": " + e.getMessage() : e.getMessage();
                    uploads.computeIfPresent(uploadId, (k, v) -> v.failed(failure));
                } finally {
                    removeDirectory(temp.getParent());
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            uploads.remove(uploadId);
            removeDirectory(temp.getParent());
            throw shuttingDown;
        }
        return Map.of("uploadId", uploadId);
    }

    @PreDestroy
    void shutdownDeliveries() {
        deliveries.shutdownNow();
    }

    /**
     * The browser's file name reduced to its last segment, by either separator, whatever this OS uses. The
     * result stays as sent, whitespace included, if it's a single relative segment on this host. Anything
     * else gets a default.
     */
    static String lastSegmentOrDefault(String name) {
        if (name == null) {
            return DEFAULT_NAME;
        }
        String last = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return isSingleSegment(last) ? last : DEFAULT_NAME;
    }

    private static boolean isSingleSegment(String name) {
        if (name.isBlank() || name.chars().allMatch(c -> c == '.')) {
            return false;
        }
        try {
            Path path = Path.of(name);
            return path.getRoot() == null && path.getNameCount() == 1;
        } catch (InvalidPathException refused) {
            return false;
        }
    }

    /** Where an upload goes: directly in {@code directory}, under the default name if {@code name} would land elsewhere. */
    private static Path uploadPath(Path directory, String name) {
        return uploadPath(directory, name, directory::resolve);
    }

    static Path uploadPath(Path directory, String name, Function<String, Path> resolve) {
        Path resolved = resolve.apply(name).normalize();
        return directory.normalize().equals(resolved.getParent()) ? resolved : directory.resolve(DEFAULT_NAME);
    }

    /**
     * Writes an upload to a new temp directory of its own, under its last segment. The browser's file name
     * is client input. On a failure, the directory goes too.
     */
    private static Path store(String prefix, MultipartFile file) throws IOException {
        Path directory = Files.createTempDirectory(prefix);
        try {
            Path temp = createUpload(directory, lastSegmentOrDefault(file.getOriginalFilename()));
            file.transferTo(temp);
            return temp;
        } catch (IOException | RuntimeException failed) {
            removeDirectory(directory);
            throw failed;
        }
    }

    /** A name this host's file system refuses, such as one over its length limit, gets the default. */
    private static Path createUpload(Path directory, String name) throws IOException {
        try {
            return Files.createFile(uploadPath(directory, name));
        } catch (FileSystemException refused) {
            return Files.createFile(directory.resolve(DEFAULT_NAME));
        }
    }

    private static void removeDirectory(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    @GetMapping("/api/uploads/{uploadId}")
    public UploadState uploadState(@PathVariable String uploadId) {
        guard.session(); // same login requirement as every other case endpoint
        UploadState state = uploads.get(uploadId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown upload");
        }
        return state;
    }

    @GetMapping("/api/requests/{token}/attachments/config")
    public AttachmentConfigDto getConfig(@PathVariable String token) {
        return guard.session().attachments().getAttachmentConfig(token);
    }

    @PostMapping("/api/requests/{token}/attachments")
    public List<AttachmentForwardResultDto> forward(
        @PathVariable String token,
        @RequestParam("description") String description,
        @RequestParam("files") List<MultipartFile> files
    ) {
        var session = guard.session();
        List<Path> tempFiles = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                tempFiles.add(store("tsanet-demo-attach", file));
            }
            return SessionGuard.sdkInput(() -> session.attachments().forwardAttachments(token, description, tempFiles));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (Path temp : tempFiles) {
                removeDirectory(temp.getParent());
            }
        }
    }
}
