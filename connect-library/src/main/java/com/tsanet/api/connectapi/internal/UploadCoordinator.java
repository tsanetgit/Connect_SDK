package com.tsanet.api.connectapi.internal;

import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrant.UploadMode;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadLink;
import com.tsanet.api.attachments.v2.UploadProgress;
import com.tsanet.api.attachments.v2.UploadProgressListener;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Uploads one grant's file through its links, part by part, in order.
 *
 * <ul>
 *   <li><b>Links just before use.</b> Links are requested when the upload reaches a part that
 *       has none, for that part and the ones after it, at most {@value #MAX_LINKS_PER_CALL} per
 *       call.</li>
 *   <li><b>Refresh.</b> A link that expires within the margin (60 seconds by default) is not
 *       used. When the upload reaches one, the rest of its batch is as old, so it asks again
 *       for that part and the ones after it in one call; before a retried {@code PUT} it asks
 *       again for that number alone. A link that was just issued is used as it is, even
 *       inside the margin, so a short link lifetime can't loop. A {@code 403} from the
 *       storage also asks for that number again and retries.</li>
 *   <li><b>A link that can't be refreshed.</b> Some receivers return the same link every time.
 *       Getting the same URL and headers back is fine while the link is still valid; once it
 *       is past its expiry the upload fails with
 *       {@link AttachmentV2Exception#LINK_NOT_REFRESHABLE}.</li>
 *   <li><b>Retry budget.</b> Each part gets {@value #MAX_ATTEMPTS} attempts, shared by every
 *       retry kind: a {@code 403} refresh, and the same link again after an I/O failure, a
 *       {@code 429} or a {@code 5xx} (with backoff, {@code Retry-After} honored and capped).
 *       Any other status is final.</li>
 * </ul>
 *
 * <p>Nothing here completes or abandons a grant; the caller does. No message names a URL or a
 * header value.
 */
class UploadCoordinator {

    static final Duration LINK_EXPIRY_MARGIN = Duration.ofSeconds(60);
    static final int MAX_LINKS_PER_CALL = 1000;
    static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    /** Issues links for numbered parts of one grant; a single upload has the one number 1. */
    @FunctionalInterface
    interface LinkSource {
        List<UploadLink> links(List<Integer> numbers);
    }

    /** One numbered region of the file. */
    record Region(int number, long offset, long length) {
    }

    private final UploadTransport transport;
    private final Clock clock;
    private final Duration margin;
    private final Duration baseBackoff;

    UploadCoordinator() {
        this(new UploadTransport(), Clock.systemUTC(), LINK_EXPIRY_MARGIN, Duration.ofSeconds(1));
    }

    UploadCoordinator(UploadTransport transport, Clock clock, Duration margin, Duration baseBackoff) {
        this.transport = transport;
        this.clock = clock;
        this.margin = margin;
        this.baseBackoff = baseBackoff;
    }

    UploadReceipts upload(AttachmentGrant grant, Path file, LinkSource source, UploadProgressListener listener) {
        UploadMode mode = grant.mode();
        // PROVISIONAL(tsanetgit/Connect-API-Code#182): the platform has GCS turned off, so the
        // client refuses gcsResumable grants. Once GCS is on, this refusal gives way to upload
        // support for the mode (the same refusal is in ConnectApiAttachmentsV2Gateway).
        if (mode == null || mode == UploadMode.GCS_RESUMABLE) {
            throw new AttachmentV2Exception("upload mode " + (mode == null ? "(none)" : mode.value())
                + " is not supported by this client", 0, AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE);
        }
        UploadProgressListener progress = listener == null ? UploadProgressListener.NONE : listener;
        long size = sizeOf(file);
        if (size != grant.expectedSizeBytes()) {
            throw precondition("the file is " + size + " bytes but grant " + grant.grantId() + " expects "
                + grant.expectedSizeBytes());
        }
        List<Region> regions = regions(grant, size);
        Map<Integer, UploadLink> issued = new HashMap<>();
        List<UploadReceipts.PartReceipt> receipts = new ArrayList<>();
        long sent = 0;
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            UploadLink link = issued.remove(region.number());
            UploadLink stale = null;
            if (link != null && expiresWithinMargin(link)) {
                // The links issued with this one are as old: ask again for this part and the
                // ones after it in one call, rather than one call per part.
                stale = link;
                issued.clear();
                link = null;
            }
            boolean fresh = false;
            if (link == null) {
                fetchBatch(regions, i, source, issued);
                link = issued.remove(region.number());
                fresh = true;
                if (stale != null && link.sameRequestAs(stale) && expired(link)) {
                    throw notRefreshable(region);
                }
            }
            long before = sent;
            int done = i;
            String etag = put(grant, file, region, link, fresh, source, bytes ->
                progress.onProgress(new UploadProgress(mode, done, regions.size(), before + bytes, size)));
            if (mode == UploadMode.S3_MULTIPART) {
                receipts.add(new UploadReceipts.PartReceipt(region.number(), etag));
            }
            sent += region.length();
            progress.onProgress(new UploadProgress(mode, i + 1, regions.size(), sent, size));
        }
        return new UploadReceipts(mode, size, receipts);
    }

    /** The file's numbered regions under the grant's plan; one region for a single upload. */
    static List<Region> regions(AttachmentGrant grant, long size) {
        if (grant.mode() == UploadMode.SINGLE) {
            return List.of(new Region(1, 0, size));
        }
        AttachmentGrant.UploadPlan plan = grant.plan();
        if (plan == null || plan.count() < 1 || plan.sizeBytes() < 1) {
            throw precondition("grant " + grant.grantId() + " has no usable " + grant.mode().value() + " plan");
        }
        long count = plan.count();
        long partSize = plan.sizeBytes();
        if ((count - 1) * partSize >= size || count * partSize < size) {
            throw precondition("grant " + grant.grantId() + " plans " + count + " parts of " + partSize
                + " bytes, which does not fit a file of " + size + " bytes");
        }
        List<Region> regions = new ArrayList<>(plan.count());
        for (int n = 1; n <= count; n++) {
            long offset = (n - 1) * partSize;
            regions.add(new Region(n, offset, n == count ? size - offset : partSize));
        }
        return regions;
    }

    /** Links for {@code regions[from]} and the ones after it that have none yet, at most one call's worth. */
    private void fetchBatch(List<Region> regions, int from, LinkSource source, Map<Integer, UploadLink> issued) {
        List<Integer> numbers = new ArrayList<>();
        for (int i = from; i < regions.size() && numbers.size() < MAX_LINKS_PER_CALL; i++) {
            int number = regions.get(i).number();
            if (!issued.containsKey(number)) {
                numbers.add(number);
            }
        }
        List<UploadLink> links = source.links(numbers);
        if (links == null || links.size() != numbers.size()) {
            throw precondition("asked for " + numbers.size() + " links and received "
                + (links == null ? 0 : links.size()));
        }
        for (UploadLink link : links) {
            if (!numbers.contains(link.number()) || issued.put(link.number(), link) != null) {
                throw precondition("received a link for number " + link.number() + ", which was not asked for once");
            }
        }
    }

    /** PUT one region within its budget; returns the ETag the storage answered with, if any. */
    private String put(AttachmentGrant grant, Path file, Region region, UploadLink first, boolean firstIsFresh,
                       LinkSource source, java.util.function.LongConsumer onBytes) {
        UploadLink link = first;
        boolean fresh = firstIsFresh;
        for (int attempt = 1; ; attempt++) {
            if (!fresh && expiresWithinMargin(link)) {
                link = refresh(region, link, source);
            }
            fresh = false;
            requireFits(region, link);
            UploadTransport.PutResult result;
            try {
                result = transport.put(link, file, region.offset(), region.length(), onBytes);
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new AttachmentV2Exception("part " + region.number() + " could not reach the storage after "
                        + attempt + " attempts: " + e.getClass().getSimpleName(), 0,
                        AttachmentV2Exception.UPLOAD_UNREACHABLE, e);
                }
                sleep(backoff(attempt, Optional.empty()));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AttachmentV2Exception("interrupted while uploading part " + region.number(), 0,
                    AttachmentV2Exception.INTERRUPTED, e);
            }
            int status = result.status();
            if (status / 100 == 2) {
                if (grant.mode() == UploadMode.S3_MULTIPART && result.etag().isEmpty()) {
                    throw new AttachmentV2Exception("part " + region.number()
                        + " was accepted without an ETag, so it can't be completed", status,
                        AttachmentV2Exception.UPLOAD_REJECTED);
                }
                return result.etag().orElse(null);
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw rejected(region, status, attempt);
            }
            if (status == 403) {
                link = refresh(region, link, source);
                fresh = true;
            } else if (status == 429 || status / 100 == 5) {
                sleep(backoff(attempt, result.retryAfter()));
            } else {
                throw rejected(region, status, attempt);
            }
        }
    }

    /** Ask for {@code region}'s link again; the same link back is an error only once it has expired. */
    private UploadLink refresh(Region region, UploadLink current, LinkSource source) {
        List<UploadLink> links = source.links(List.of(region.number()));
        if (links == null || links.size() != 1 || links.get(0).number() != region.number()) {
            throw precondition("asked for a fresh link for number " + region.number() + " and did not get exactly one");
        }
        UploadLink fresh = links.get(0);
        if (fresh.sameRequestAs(current) && expired(fresh)) {
            throw notRefreshable(region);
        }
        return fresh;
    }

    private static AttachmentV2Exception notRefreshable(Region region) {
        return new AttachmentV2Exception("part " + region.number()
            + ": the link has expired and asking again returned the same link", 0,
            AttachmentV2Exception.LINK_NOT_REFRESHABLE);
    }

    private boolean expiresWithinMargin(UploadLink link) {
        return link.expiresAt() != null && !link.expiresAt().isAfter(now().plus(margin));
    }

    private boolean expired(UploadLink link) {
        return link.expiresAt() != null && !link.expiresAt().isAfter(now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static void requireFits(Region region, UploadLink link) {
        if (link.sizeBytes() != null && link.sizeBytes() != region.length()) {
            throw precondition("the link for number " + region.number() + " takes " + link.sizeBytes()
                + " bytes but the plan gives that part " + region.length());
        }
    }

    private static AttachmentV2Exception rejected(Region region, int status, int attempts) {
        return new AttachmentV2Exception("part " + region.number() + " was rejected by the storage: HTTP " + status
            + " after " + attempts + (attempts == 1 ? " attempt" : " attempts"), status,
            AttachmentV2Exception.UPLOAD_REJECTED);
    }

    Duration backoff(int attempt, Optional<String> retryAfter) {
        if (retryAfter.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfter.get().trim());
                return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER.toSeconds()));
            } catch (NumberFormatException ignored) {
                // Fall through to exponential backoff for date-formatted values.
            }
        }
        long millis = baseBackoff.toMillis() * (1L << (attempt - 1));
        long jitter = millis == 0 ? 0 : ThreadLocalRandom.current().nextLong(millis / 2 + 1);
        return Duration.ofMillis(millis + jitter);
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentV2Exception("interrupted while backing off", 0, AttachmentV2Exception.INTERRUPTED, e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            long size = Files.size(file);
            if (size < 1) {
                throw precondition("the file is empty; a grant needs at least one byte");
            }
            return size;
        } catch (IOException e) {
            throw new AttachmentV2Exception("cannot read the file to upload: " + e.getClass().getSimpleName(), 0,
                AttachmentV2Exception.CLIENT_PRECONDITION, e);
        }
    }

    private static AttachmentV2Exception precondition(String message) {
        return new AttachmentV2Exception(message, 0, AttachmentV2Exception.CLIENT_PRECONDITION);
    }
}
