package com.tsanet.api.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The delivery paragraph of {@link AttachmentsV2Facade#send}'s javadoc is copied into
 * docs/attachments-v2-client.md between sync markers, because members read the guide where the
 * javadoc isn't rendered. The javadoc is the source: when it changes, copy it into the guide.
 * Javadoc code tags become backticks before the two are compared.
 */
class SendDocSyncTest {

    private static final Path FACADE = Path.of("src/main/java/com/tsanet/api/facade/AttachmentsV2Facade.java");
    private static final Path GUIDE = Path.of("../docs/attachments-v2-client.md");
    private static final String OPEN = "<!-- sync: AttachmentsV2Facade.send.";
    private static final String CLOSE = "<!-- /sync -->";

    @Test
    void theGuideCarriesSendsDeliveryParagraphWordForWord() throws IOException {
        String javadoc = javadocParagraph();
        String guide = guideParagraph();

        assertThat(javadoc).as("send()'s <p> paragraph in %s", FACADE).isNotBlank();
        assertThat(guide)
            .as("the paragraph between the sync markers in %s must equal send()'s javadoc <p> paragraph", GUIDE)
            .isEqualTo(javadoc);
    }

    /** The first {@code <p>} paragraph of the javadoc just above the one {@code send(}. */
    private static String javadocParagraph() throws IOException {
        List<String> lines = Files.readAllLines(FACADE);
        List<Integer> sends = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("AttachmentGrant send(")) {
                sends.add(i);
            }
        }
        assertThat(sends).as("one send( in %s; with an overload, say which javadoc the guide copies", FACADE)
            .hasSize(1);
        int send = sends.get(0);
        int start = send;
        while (start >= 0 && !lines.get(start).contains("/**")) {
            start--;
        }
        assertThat(start).as("send()'s javadoc in %s", FACADE).isNotNegative();

        List<String> paragraph = new ArrayList<>();
        for (int i = start; i < send; i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("*/")) {
                break;
            }
            String text = line.replaceFirst("^\\*\\s?", "");
            if (paragraph.isEmpty()) {
                if (text.startsWith("<p>")) {
                    paragraph.add(text.substring("<p>".length()));
                }
            } else if (text.isEmpty() || text.startsWith("@")) {
                break;
            } else {
                paragraph.add(text);
            }
        }
        return normalize(String.join(" ", paragraph).replaceAll("\\{@code ([^}]*)}", "`$1`"));
    }

    private static String guideParagraph() throws IOException {
        String guide = Files.readString(GUIDE);
        int open = guide.indexOf(OPEN);
        assertThat(open).as("%s in %s", OPEN, GUIDE).isNotNegative();
        int body = guide.indexOf("-->", open) + "-->".length();
        int close = guide.indexOf(CLOSE, body);
        assertThat(close).as("%s after the opening marker in %s", CLOSE, GUIDE).isPositive();
        return normalize(guide.substring(body, close));
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
