package com.tsanet.demo.web;

import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.UploadProgress;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.facade.AttachmentsV2Facade;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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

    private final SessionGuard guard;
    /** In-flight and finished V2 deliveries, keyed by upload id; demo-side state only. */
    private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();
    private final ExecutorService deliveries = Executors.newVirtualThreadPerTaskExecutor();

    public AttachmentsController(SessionGuard guard) {
        this.guard = guard;
    }

    /**
     * What the page polls: phase, the grant's mode, parts done of total, bytes sent of total,
     * and once finished the platform's recorded outcome or the failure message. The outcome
     * is the facade's return value verbatim; the demo never synthesizes a status.
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
        AttachmentCompleteResult outcome,
        String error
    ) {
        UploadState progressed(UploadProgress p) {
            return new UploadState(uploadId, fileName, "uploading", p.mode(), p.partsDone(), p.partsTotal(),
                p.bytesSent(), p.bytesTotal(), null, null);
        }

        UploadState finished(AttachmentCompleteResult result) {
            return new UploadState(uploadId, fileName, "done", mode, partsDone, partsTotal, bytesSent, bytesTotal, result, null);
        }

        UploadState failed(String message) {
            return new UploadState(uploadId, fileName, "failed", mode, partsDone, partsTotal, bytesSent, bytesTotal, null, message);
        }
    }

    /**
     * Direct delivery (V2): the file goes from this demo straight into the partner's store
     * under the platform's instructions, then the platform verifies and posts the note. Runs
     * on a background thread because a large file takes a while; the page polls
     * {@link #uploadState}.
     */
    @PostMapping("/api/requests/{token}/attachments/v2")
    public Map<String, String> deliver(
        @PathVariable String token,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "description", required = false) String description,
        @RequestParam(value = "sha256", defaultValue = "false") boolean sha256
    ) {
        // Resolve the facade here, on the request thread: an account-scoped session picks its
        // delegate per call, and a delivery must not follow an account switch made mid-flight.
        AttachmentsV2Facade attachments = guard.session().attachmentsV2();
        // The browser's file name is client input; keep only its last segment before it
        // becomes a path under the temp directory.
        String original = file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()
            ? Path.of(file.getOriginalFilename()).getFileName().toString() : "attachment";
        Path temp;
        try {
            temp = Files.createTempDirectory("tsanet-demo-deliver").resolve(original);
            file.transferTo(temp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new UploadState(uploadId, original, "starting", null, 0, 0, 0, file.getSize(), null, null));
        deliveries.submit(() -> {
            try {
                AttachmentCompleteResult outcome = attachments.send(token, temp, file.getContentType(),
                    description, sha256, progress -> uploads.computeIfPresent(uploadId, (k, v) -> v.progressed(progress)));
                uploads.computeIfPresent(uploadId, (k, v) -> v.finished(outcome));
            } catch (RuntimeException e) {
                uploads.computeIfPresent(uploadId, (k, v) -> v.failed(e.getMessage()));
            } finally {
                try {
                    Files.deleteIfExists(temp);
                    Files.deleteIfExists(temp.getParent());
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        });
        return Map.of("uploadId", uploadId);
    }

    @PreDestroy
    void shutdownDeliveries() {
        deliveries.shutdownNow();
    }

    @GetMapping("/api/uploads/{uploadId}")
    public UploadState uploadState(@PathVariable String uploadId) {
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
                String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment";
                Path temp = Files.createTempDirectory("tsanet-demo-attach").resolve(original);
                file.transferTo(temp);
                tempFiles.add(temp);
            }
            return session.attachments().forwardAttachments(token, description, tempFiles);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (Path temp : tempFiles) {
                try {
                    Files.deleteIfExists(temp);
                    Files.deleteIfExists(temp.getParent());
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }
}
