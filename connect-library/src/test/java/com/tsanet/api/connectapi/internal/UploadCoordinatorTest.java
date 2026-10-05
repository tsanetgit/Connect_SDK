package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrant.UploadMode;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadLink;
import com.tsanet.api.attachments.v2.UploadProgress;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The upload loop against a scripted transport and a recording link source, no HTTP: links
 * requested just before use in batches of at most 1,000, refreshed inside the margin and
 * after a 403, a receiver link that can't be refreshed, one shared retry budget per part, and
 * no URL or header value in any message.
 */
class UploadCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");
    private static final String SECRET_URL = "SENTINEL-URL";
    private static final String SECRET_HEADER = "SENTINEL-HDR";

    @TempDir
    Path tmp;

    private final MutableClock clock = new MutableClock(T0);
    private final ScriptedTransport transport = new ScriptedTransport();
    private final RecordingLinks links = new RecordingLinks();
    private UploadCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new UploadCoordinator(transport, clock, Duration.ofSeconds(60), Duration.ZERO);
    }

    // ---------- fixtures ----------

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** One PUT as the transport saw it. */
    record Put(int number, String url, long offset, long length) {
    }

    /** Answers each PUT from a per-number script; an empty script answers 201 with an ETag. */
    final class ScriptedTransport extends UploadTransport {
        final List<Put> puts = new ArrayList<>();
        final Map<Integer, Deque<Object>> script = new HashMap<>();
        boolean noEtag;
        Runnable onPut = () -> { };

        ScriptedTransport() {
            super(null);
        }

        void answer(int number, Object... outcomes) {
            script.computeIfAbsent(number, k -> new ArrayDeque<>()).addAll(List.of(outcomes));
        }

        @Override
        PutResult put(UploadLink link, Path file, long offset, long length, LongConsumer onBytes)
            throws IOException, InterruptedException {
            puts.add(new Put(link.number(), link.url(), offset, length));
            onPut.run();
            Object next = script.getOrDefault(link.number(), new ArrayDeque<>()).poll();
            if (next instanceof IOException e) {
                throw e;
            }
            if (next instanceof InterruptedException e) {
                throw e;
            }
            int status = next == null ? 201 : (Integer) next;
            Optional<String> etag = noEtag || status / 100 != 2 ? Optional.empty() : Optional.of("\"etag-" + link.number() + "\"");
            return new PutResult(status, etag, Optional.empty());
        }
    }

    /** Issues numbered links and records every call; {@code expiry} decides each link's lifetime. */
    final class RecordingLinks implements UploadCoordinator.LinkSource {
        final List<List<Integer>> calls = new ArrayList<>();
        final Map<Integer, Integer> issuedCount = new HashMap<>();
        BiFunction<Integer, Integer, OffsetDateTime> expiry = (number, version) -> now().plusMinutes(30);
        boolean sameEveryTime;

        @Override
        public List<UploadLink> links(List<Integer> numbers) {
            calls.add(List.copyOf(numbers));
            List<UploadLink> out = new ArrayList<>();
            for (int n : numbers) {
                int version = issuedCount.merge(n, 1, Integer::sum);
                int v = sameEveryTime ? 1 : version;
                out.add(new UploadLink(n, "https://store.example/" + SECRET_URL + "/" + n + "/v" + v,
                    Map.of("x-sig", SECRET_HEADER), null, expiry.apply(n, v)));
            }
            return out;
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Path file(int size) throws IOException {
        Path path = tmp.resolve("f-" + size + ".bin");
        Files.write(path, new byte[size]);
        return path;
    }

    private static AttachmentGrant grant(UploadMode mode, long size, AttachmentGrant.UploadPlan plan) {
        return new AttachmentGrant(42, AttachmentGrant.Status.OPEN, "f.bin", size, null, null, mode, plan);
    }

    private static AttachmentGrant s3(long size, int count, long partSize) {
        return grant(UploadMode.S3_MULTIPART, size, new AttachmentGrant.UploadPlan(count, partSize));
    }

    // ---------- the plan ----------

    @Test
    void s3UploadsEveryRegionInOrderAndKeepsEachEtagExactly() throws IOException {
        List<UploadProgress> progress = new ArrayList<>();

        UploadReceipts receipts = coordinator.upload(s3(10, 3, 4), file(10), links, progress::add);

        assertThat(transport.puts).extracting(Put::number, Put::offset, Put::length)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 0L, 4L),
                org.assertj.core.groups.Tuple.tuple(2, 4L, 4L),
                org.assertj.core.groups.Tuple.tuple(3, 8L, 2L));
        assertThat(receipts.parts()).containsExactly(new UploadReceipts.PartReceipt(1, "\"etag-1\""),
            new UploadReceipts.PartReceipt(2, "\"etag-2\""), new UploadReceipts.PartReceipt(3, "\"etag-3\""));
        assertThat(receipts.bytesSent()).isEqualTo(10);
        assertThat(progress.get(progress.size() - 1)).isEqualTo(new UploadProgress(UploadMode.S3_MULTIPART, 3, 3, 10, 10));
    }

    @Test
    void singleAndAzureUploadsCarryNoReceipts() throws IOException {
        UploadReceipts single = coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null);
        assertThat(single.parts()).isEmpty();
        assertThat(transport.puts).extracting(Put::length).containsExactly(5L);

        UploadReceipts azure = coordinator.upload(grant(UploadMode.AZURE_BLOCK, 5,
            new AttachmentGrant.UploadPlan(2, 3)), file(5), links, null);
        assertThat(azure.parts()).isEmpty();
    }

    @Test
    void linksAreRequestedJustBeforeUseAtMostAThousandPerCall() throws IOException {
        List<Integer> callsBeforePut = new ArrayList<>();
        transport.onPut = () -> callsBeforePut.add(links.calls.size());

        coordinator.upload(s3(2500, 2500, 1), file(2500), links, null);

        assertThat(links.calls).extracting(List::size).containsExactly(1000, 1000, 500);
        assertThat(links.calls.get(1).get(0)).isEqualTo(1001);
        // The second batch is asked for only when part 1001 is reached, not up front.
        assertThat(callsBeforePut.get(999)).isEqualTo(1);
        assertThat(callsBeforePut.get(1000)).isEqualTo(2);
    }

    @Test
    void aFileThatDoesNotMatchTheGrantFailsBeforeAnyLinkIsRequested() throws IOException {
        assertThatThrownBy(() -> coordinator.upload(s3(10, 3, 4), file(9), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.CLIENT_PRECONDITION));
        assertThat(links.calls).isEmpty();
    }

    @Test
    void aPlanThatDoesNotFitTheFileFailsBeforeAnyLinkIsRequested() throws IOException {
        // 2 x 4 = 8 bytes can't hold 10; 4 x 4 leaves a whole part empty.
        assertThatThrownBy(() -> coordinator.upload(s3(10, 2, 4), file(10), links, null))
            .isInstanceOf(AttachmentV2Exception.class);
        assertThatThrownBy(() -> coordinator.upload(s3(10, 4, 4), file(10), links, null))
            .isInstanceOf(AttachmentV2Exception.class);
        assertThat(links.calls).isEmpty();
    }

    @Test
    void aLinkWhoseSizeDisagreesWithThePlanIsRefusedBeforeThePut() throws IOException {
        UploadCoordinator.LinkSource wrongSize = numbers -> numbers.stream()
            .map(n -> new UploadLink(n, "https://store.example/x", Map.of(), 99L, now().plusMinutes(30))).toList();

        assertThatThrownBy(() -> coordinator.upload(s3(10, 3, 4), file(10), wrongSize, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("number 1");
        assertThat(transport.puts).isEmpty();
    }

    @Test
    void gcsResumableIsRefusedBeforeAnyCall() throws IOException {
        assertThatThrownBy(() -> coordinator.upload(grant(UploadMode.GCS_RESUMABLE, 5, null), file(5), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code())
                .isEqualTo(AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE));
        assertThat(links.calls).isEmpty();
    }

    @Test
    void anS3PartAcceptedWithoutAnEtagFails() throws IOException {
        transport.noEtag = true;

        assertThatThrownBy(() -> coordinator.upload(s3(10, 3, 4), file(10), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("without an ETag");
    }

    // ---------- refresh ----------

    @Test
    void aBatchInsideTheMarginIsAskedForAgainInOneCall() throws IOException {
        // Every link issued in the first batch expires 30 seconds out: inside the 60 second margin.
        links.expiry = (number, version) -> version == 1 ? now().plusSeconds(30) : now().plusMinutes(30);

        coordinator.upload(s3(10, 3, 4), file(10), links, null);

        // Part 1's link was just issued and is used as it is; at part 2 the rest of the batch is
        // as old, so parts 2 and 3 are asked for again together.
        assertThat(links.calls).containsExactly(List.of(1, 2, 3), List.of(2, 3));
        assertThat(transport.puts).extracting(Put::url).containsExactly(
            "https://store.example/" + SECRET_URL + "/1/v1",
            "https://store.example/" + SECRET_URL + "/2/v2",
            "https://store.example/" + SECRET_URL + "/3/v2");
    }

    @Test
    void aRetryAfterTheLinkAgedAsksForThatNumberAlone() throws IOException {
        // Part 1's first PUT answers 503 while the clock runs to 30 seconds before every
        // first-batch link expires.
        transport.answer(1, 503);
        transport.onPut = () -> {
            if (transport.puts.size() == 1) {
                clock.advance(Duration.ofMinutes(30).minusSeconds(30));
            }
        };

        coordinator.upload(s3(10, 3, 4), file(10), links, null);

        assertThat(links.calls).containsExactly(List.of(1, 2, 3), List.of(1), List.of(2, 3));
        assertThat(transport.puts).extracting(Put::url).containsExactly(
            "https://store.example/" + SECRET_URL + "/1/v1",
            "https://store.example/" + SECRET_URL + "/1/v2",
            "https://store.example/" + SECRET_URL + "/2/v2",
            "https://store.example/" + SECRET_URL + "/3/v2");
    }

    @Test
    void aLinkIssuedInsideTheMarginIsUsedRatherThanRefreshedForever() throws IOException {
        // Every link ever issued lives 10 seconds: shorter than the margin.
        links.expiry = (number, version) -> now().plusSeconds(10);

        coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null);

        assertThat(links.calls).hasSize(1);
        assertThat(transport.puts).hasSize(1);
    }

    @Test
    void a403AsksForThatNumberAgainAndRetriesWithTheFreshLink() throws IOException {
        transport.answer(2, 403);

        coordinator.upload(s3(10, 3, 4), file(10), links, null);

        assertThat(links.calls).containsExactly(List.of(1, 2, 3), List.of(2));
        assertThat(transport.puts).extracting(Put::url).containsExactly(
            "https://store.example/" + SECRET_URL + "/1/v1",
            "https://store.example/" + SECRET_URL + "/2/v1",
            "https://store.example/" + SECRET_URL + "/2/v2",
            "https://store.example/" + SECRET_URL + "/3/v1");
    }

    @Test
    void anExpiredLinkThatComesBackUnchangedCannotBeRefreshed() throws IOException {
        links.sameEveryTime = true;
        links.expiry = (number, version) -> T0.atOffset(ZoneOffset.UTC).plusMinutes(5);
        transport.onPut = () -> clock.advance(Duration.ofMinutes(10));

        // Part 1 goes out, the clock passes the link's expiry, part 2's link is asked for again
        // and comes back identical and expired.
        assertThatThrownBy(() -> coordinator.upload(s3(10, 3, 4), file(10), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code())
                .isEqualTo(AttachmentV2Exception.LINK_NOT_REFRESHABLE))
            .hasMessageContaining("part 2");
        assertThat(transport.puts).hasSize(1);
    }

    @Test
    void a403OnALinkThatExpiredMidPutAndComesBackUnchangedCannotBeRefreshed() throws IOException {
        links.sameEveryTime = true;
        links.expiry = (number, version) -> T0.atOffset(ZoneOffset.UTC).plusMinutes(5);
        transport.onPut = () -> clock.advance(Duration.ofMinutes(10));
        transport.answer(1, 403);

        assertThatThrownBy(() -> coordinator.upload(s3(10, 3, 4), file(10), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code())
                .isEqualTo(AttachmentV2Exception.LINK_NOT_REFRESHABLE))
            .hasMessageContaining("part 1");
        assertThat(links.calls).containsExactly(List.of(1, 2, 3), List.of(1));
    }

    @Test
    void anUnchangedLinkThatIsStillValidIsReusedAfterA403UntilTheBudgetRunsOut() throws IOException {
        links.sameEveryTime = true;
        transport.answer(1, 403, 403, 403);

        assertThatThrownBy(() -> coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.UPLOAD_REJECTED);
                assertThat(ex.status()).isEqualTo(403);
            });
        assertThat(transport.puts).hasSize(UploadCoordinator.MAX_ATTEMPTS);
    }

    // ---------- the retry budget ----------

    @Test
    void transientFailuresRetryTheSameLink() throws IOException {
        transport.answer(1, 503, new IOException("reset"));

        coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null);

        assertThat(transport.puts).extracting(Put::url).containsOnly("https://store.example/" + SECRET_URL + "/1/v1");
        assertThat(transport.puts).hasSize(3);
        assertThat(links.calls).hasSize(1);
    }

    @Test
    void oneBudgetPerPartIsSharedByEveryKindOfRetry() throws IOException {
        transport.answer(1, 403, new IOException("reset"), 500, 201);

        assertThatThrownBy(() -> coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).status()).isEqualTo(500));
        assertThat(transport.puts).hasSize(UploadCoordinator.MAX_ATTEMPTS);
    }

    @Test
    void anIoFailureOnTheLastAttemptIsUnreachable() throws IOException {
        transport.answer(1, new IOException("a"), new IOException("b"), new IOException("c"));

        assertThatThrownBy(() -> coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.UPLOAD_UNREACHABLE));
    }

    @Test
    void anyOtherClientErrorIsFinalAtOnce() throws IOException {
        transport.answer(1, 400);

        assertThatThrownBy(() -> coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).status()).isEqualTo(400));
        assertThat(transport.puts).hasSize(1);
        assertThat(links.calls).hasSize(1);
    }

    @Test
    void retryAfterIsHonoredAndCapped() {
        assertThat(coordinator.backoff(1, Optional.of("7"))).isEqualTo(Duration.ofSeconds(7));
        assertThat(coordinator.backoff(1, Optional.of("3600"))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void anInterruptDuringAPutIsReportedAsAnInterruptAndKeepsTheFlag() throws IOException {
        transport.answer(1, new InterruptedException());
        try {
            assertThatThrownBy(() -> coordinator.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null))
                .isInstanceOf(AttachmentV2Exception.class)
                .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(transport.puts).hasSize(1);
    }

    @Test
    void anInterruptDuringTheBackoffIsReportedAsAnInterrupt() throws IOException {
        UploadCoordinator backingOff = new UploadCoordinator(transport, clock, Duration.ofSeconds(60), Duration.ofMillis(50));
        transport.answer(1, 500);
        transport.onPut = () -> Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> backingOff.upload(grant(UploadMode.SINGLE, 5, null), file(5), links, null))
                .isInstanceOf(AttachmentV2Exception.class)
                .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(transport.puts).hasSize(1);
    }

    @Test
    void aLinkWithoutAUrlCannotBeBuilt() {
        assertThatThrownBy(() -> new UploadLink(1, null, Map.of(), null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("url");
    }

    // ---------- links are credentials ----------

    @Test
    void noFailureMessageOrConsoleLineCarriesAUrlOrHeaderValue() throws IOException {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        List<Throwable> failures = new ArrayList<>();
        try {
            System.setOut(new PrintStream(console, true));
            System.setErr(new PrintStream(console, true));
            Path f = file(10);

            transport.answer(1, 400);
            failures.add(catchFailure(() -> coordinator.upload(s3(10, 3, 4), f, links, null)));

            transport.script.clear();
            transport.answer(1, new IOException("x"), new IOException("y"), new IOException("z"));
            failures.add(catchFailure(() -> coordinator.upload(s3(10, 3, 4), f, links, null)));

            transport.script.clear();
            links.sameEveryTime = true;
            links.expiry = (number, version) -> now().minusMinutes(1);
            transport.answer(1, 403);
            failures.add(catchFailure(() -> coordinator.upload(s3(10, 3, 4), f, links, null)));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertThat(failures).hasSize(3).doesNotContainNull();
        for (Throwable failure : failures) {
            for (String text : everyMessageIn(failure)) {
                assertThat(text).doesNotContain(SECRET_URL).doesNotContain(SECRET_HEADER);
            }
        }
        assertThat(console.toString()).doesNotContain(SECRET_URL).doesNotContain(SECRET_HEADER);
    }

    private static Throwable catchFailure(Runnable run) {
        try {
            run.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** Messages and toStrings of the failure, its causes and everything suppressed along the way. */
    private static List<String> everyMessageIn(Throwable failure) {
        List<String> texts = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>(List.of(failure));
        while (!pending.isEmpty()) {
            Throwable t = pending.pop();
            texts.add(String.valueOf(t.getMessage()));
            texts.add(t.toString());
            if (t.getCause() != null) {
                pending.push(t.getCause());
            }
            for (Throwable s : t.getSuppressed()) {
                pending.push(s);
            }
        }
        return texts;
    }
}
