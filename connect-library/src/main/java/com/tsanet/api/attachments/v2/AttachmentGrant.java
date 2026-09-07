package com.tsanet.api.attachments.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A grant: permission plus upload instructions for one file on one case, from the
 * {@code AttachmentGrantDTO} schema of the V2 contract draft (tsanetgit/Connect-API-Code#147).
 * The client executes {@link Upload} verbatim and never branches on the receiver.
 *
 * <p>{@code toString} on every record that carries a URL or headers is redacted: signed URLs
 * and the headers block are credentials, and a record's default {@code toString} would put
 * them into any log line or exception message that interpolates the grant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachmentGrant(
    UUID grantId,
    String fileName,
    OffsetDateTime expiresAt,
    Receiver receiver,
    Upload upload,
    Verification verification
) {
    @Override
    public String toString() {
        return "AttachmentGrant[grantId=" + grantId + ", fileName=" + fileName + ", expiresAt=" + expiresAt
            + ", receiver=" + receiver + ", upload=" + upload + ", verification=" + verification + "]";
    }

    /** What the platform echoes about the receiver so a client can fail fast. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Receiver(Long companyId, String targetKind, Long maxSizeBytes) {
    }

    /**
     * The upload instructions. {@code mode} is one of {@code single}, {@code multipart},
     * {@code resumable}, {@code relay}; {@code headers} are sent verbatim on every request.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Upload(String mode, String method, String url, Map<String, String> headers, List<Part> parts) {
        public Upload {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        @Override
        public String toString() {
            return "Upload[mode=" + mode + ", method=" + method + ", url=<redacted>, headers="
                + headers.keySet() + ", parts=" + parts.size() + "]";
        }
    }

    /** One presigned part URL in multipart mode; sizes are fixed by the platform. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(int partNumber, String url, long sizeBytes) {
        @Override
        public String toString() {
            return "Part[partNumber=" + partNumber + ", url=<redacted>, sizeBytes=" + sizeBytes + "]";
        }
    }

    /** What complete will be able to prove: {@code platform}, {@code receiver_reported} or {@code none}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verification(String mode) {
    }
}
