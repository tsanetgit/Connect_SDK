package com.tsanet.api.attachments.v2;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * One signed upload link, mapped from the generated {@code SingleUploadUrlDTO},
 * {@code S3PartDTO} or {@code AzureBlockDTO}. Send exactly {@link #headers()} with the
 * {@code PUT}, and nothing else.
 *
 * <p>A link is a credential: {@link #toString()} leaves out the URL and the header values,
 * so a log line or exception message that interpolates a link can't leak it.
 *
 * @param number    the part or block number; 1 for a single upload
 * @param url       the signed URL; required
 * @param headers   headers to send unchanged; empty when the platform sent none
 * @param sizeBytes the byte count this link accepts; null for a single upload, which takes the
 *                  grant's {@code expectedSizeBytes}
 * @param expiresAt the link stops working at this time, never later than the grant's own expiry
 */
public record UploadLink(int number, String url, Map<String, String> headers, Long sizeBytes, OffsetDateTime expiresAt) {

    public UploadLink {
        Objects.requireNonNull(url, "url");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Whether {@code other} would send the same request: same URL and same headers. */
    public boolean sameRequestAs(UploadLink other) {
        return other != null && url.equals(other.url) && headers.equals(other.headers);
    }

    @Override
    public String toString() {
        return "UploadLink[number=" + number + ", url=<redacted>, headers=" + headers.keySet()
            + ", sizeBytes=" + sizeBytes + ", expiresAt=" + expiresAt + "]";
    }
}
