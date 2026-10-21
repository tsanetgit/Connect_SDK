package com.tsanet.api.facade;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * docs/attachments-v2-client.md copies text from the javadoc, because members read the guide
 * where the javadoc isn't rendered. The javadoc is the source: when it changes, copy it into the
 * guide. Javadoc code and link tags become backticks before the two are compared.
 *
 * <p>Text the README and the guide both carry sits in named blocks, {@code <!-- sync: name -->}
 * to {@code <!-- /sync: name -->}, and each block must read the same in both files. A block's
 * inline Markdown links and images ({@code [text](target)}) target only absolute URLs: the two
 * files are different pages in different directories, so a relative target or {@code #anchor}
 * would resolve differently in each. Reference-style links aren't checked; no block uses one.
 */
class AttachmentsV2GuideSyncTest {

    // Resolved from the module, not the working directory, so the test also runs from the repository root, or from
    // an IDE that compiles into the module's target directory, as a Maven import does.
    private static final Path MODULE = moduleRoot();
    private static final Path FACADE = MODULE.resolve("src/main/java/com/tsanet/api/facade/AttachmentsV2Facade.java");
    private static final Path EXCEPTION = MODULE.resolve("src/main/java/com/tsanet/api/attachments/v2/AttachmentV2Exception.java");
    private static final Path GUIDE = MODULE.resolve("../docs/attachments-v2-client.md").normalize();
    private static final Path README = MODULE.resolve("README.md");
    private static final String OPEN = "<!-- sync: AttachmentsV2Facade.send.";
    private static final String CLOSE = "<!-- /sync -->";
    private static final String TABLE_HEADER = "| Code | Cause |";
    private static final Pattern TABLE_ROW = Pattern.compile("^\\| `([^`]+)` \\| (.*) \\|$");
    private static final Pattern NAMED_OPEN = Pattern.compile("^<!-- sync: ([a-z0-9-]+) -->$");
    private static final Pattern MARKDOWN_LINK_TARGET = Pattern.compile("\\]\\(\\s*([^)\\s]+)");
    private static final Pattern ABSOLUTE_TARGET = Pattern.compile("^(https?://|mailto:).*");
    private static final Pattern ANY_SYNC_MARKER = Pattern.compile("<!--\\s*/?\\s*sync\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void theGuideCarriesSendsJavadocWordForWord() throws IOException {
        List<String> javadoc = sendJavadocParagraphs();
        List<String> guide = guideParagraphs();

        assertThat(javadoc).as("send()'s javadoc paragraphs in %s", FACADE).hasSizeGreaterThan(1);
        assertThat(guide)
            .as("the paragraphs between the sync markers in %s must equal send()'s javadoc", GUIDE)
            .isEqualTo(javadoc);
    }

    /** The guide's code table has one row per code constant, and each row's cause is that constant's javadoc. */
    @Test
    void theCodeTableCarriesTheConstantsJavadoc() throws IOException, IllegalAccessException {
        Map<String, String> table = codeTable();
        List<String> codes = new ArrayList<>();
        for (Field field : AttachmentV2Exception.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && field.getType() == String.class) {
                codes.add((String) field.get(null));
            }
        }
        assertThat(codes).as("the public String constants of %s", AttachmentV2Exception.class).isNotEmpty();
        assertThat(table.keySet()).as("the codes in %s's table, against the constants in %s", GUIDE, EXCEPTION)
            .containsExactlyInAnyOrderElementsOf(codes);

        for (String code : codes) {
            String cause = table.get(code);
            String javadoc = constantJavadoc(code);
            String expected = startLowercase(javadoc).replaceFirst("\\.$", "");
            assertThat(cause)
                .as("the %s row in %s must equal the javadoc of its constant in %s", code, GUIDE, EXCEPTION)
                .isEqualTo(expected);
        }
    }

    /** Every named block appears in both the README and the guide, and reads the same in both. */
    @Test
    void theReadmeAndTheGuideCarryEachNamedBlockWordForWord() throws IOException {
        Map<String, String> readme = namedBlocks(README);
        Map<String, String> guide = namedBlocks(GUIDE);

        assertThat(readme).as("named sync blocks in %s", README).isNotEmpty();
        assertThat(guide.keySet()).as("the named sync blocks in %s, against those in %s", GUIDE, README)
            .containsExactlyInAnyOrderElementsOf(readme.keySet());
        readme.forEach((name, text) -> assertThat(guide.get(name))
            .as("the %s block in %s must equal the one in %s", name, GUIDE, README).isEqualTo(text));
    }

    /** Name to whitespace-normalized text, for each named block in {@code file}. */
    private static Map<String, String> namedBlocks(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        Map<String, String> blocks = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            // The javadoc block's own markers; anything else that looks like a sync marker must be a named open.
            if (!ANY_SYNC_MARKER.matcher(line).find() || line.startsWith(OPEN) || line.equals(CLOSE)) {
                continue;
            }
            Matcher open = NAMED_OPEN.matcher(line);
            assertThat(open.matches()).as("a sync marker in %s reads <!-- sync: name --> (a-z, 0-9, -): %s", file, line)
                .isTrue();
            String close = "<!-- /sync: " + open.group(1) + " -->";
            int end = i + 1;
            while (end < lines.size() && !lines.get(end).trim().equals(close)) {
                end++;
            }
            assertThat(end).as("%s after %s in %s", close, line, file).isLessThan(lines.size());
            List<String> block = lines.subList(i + 1, end);
            assertThat(relativeTargets(block))
                .as("relative link, anchor or image targets in the %s block in %s", open.group(1), file).isEmpty();
            assertThat(blocks.put(open.group(1), normalize(String.join(" ", block))))
                .as("one %s block in %s", open.group(1), file).isNull();
            i = end;
        }
        return blocks;
    }

    /** The block's inline Markdown link and image targets that aren't absolute URLs, outside fenced code. */
    private static List<String> relativeTargets(List<String> block) {
        List<String> relative = new ArrayList<>();
        boolean fenced = false;
        for (String line : block) {
            if (line.trim().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher target = MARKDOWN_LINK_TARGET.matcher(line);
            while (target.find()) {
                if (!ABSOLUTE_TARGET.matcher(target.group(1)).matches()) {
                    relative.add(target.group(1));
                }
            }
        }
        return relative;
    }

    /** The paragraphs of the javadoc just above the one {@code send(}, up to its first tag. */
    private static List<String> sendJavadocParagraphs() throws IOException {
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

        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (int i = start + 1; i < send; i++) {
            String line = lines.get(i).trim();
            String text = line.replaceFirst("^\\*\\s?", "");
            boolean end = line.startsWith("*/") || text.startsWith("@");
            if (end || text.isEmpty()) {
                if (!paragraph.isEmpty()) {
                    paragraphs.add(javadocText(paragraph.toString()));
                    paragraph.setLength(0);
                }
                if (end) {
                    break;
                }
                continue;
            }
            paragraph.append(text.replaceFirst("^<p>", "")).append(' ');
        }
        return paragraphs;
    }

    /** The javadoc of the constant whose value is {@code code}. */
    private static String constantJavadoc(String code) throws IOException {
        List<String> lines = Files.readAllLines(EXCEPTION);
        List<Integer> constants = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("= \"" + code + "\";")) {
                constants.add(i);
            }
        }
        assertThat(constants).as("one constant for %s in %s", code, EXCEPTION).hasSize(1);
        int constant = constants.get(0);
        assertThat(lines.get(constant - 1).trim()).as("a javadoc ending just above the constant for %s in %s", code, EXCEPTION)
            .endsWith("*/");
        int start = constant - 1;
        while (start > 0 && !lines.get(start).contains("/**")) {
            start--;
        }
        StringBuilder text = new StringBuilder();
        for (int i = start; i < constant; i++) {
            text.append(lines.get(i).trim().replace("/**", "").replace("*/", "").replaceFirst("^\\*\\s?", ""))
                .append(' ');
        }
        String javadoc = javadocText(text.toString());
        assertThat(javadoc).as("the javadoc of the constant for %s in %s", code, EXCEPTION).isNotBlank();
        return javadoc;
    }

    /** The guide's code table, code to cause, in the guide's order. */
    private static Map<String, String> codeTable() throws IOException {
        List<String> lines = Files.readAllLines(GUIDE);
        int header = lines.indexOf(TABLE_HEADER);
        assertThat(header).as("%s in %s", TABLE_HEADER, GUIDE).isNotNegative();
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = header + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            Matcher row = TABLE_ROW.matcher(lines.get(i));
            assertThat(row.matches()).as("a | `code` | cause | row in %s: %s", GUIDE, lines.get(i)).isTrue();
            assertThat(table.put(row.group(1), normalize(row.group(2))))
                .as("one %s row in %s", row.group(1), GUIDE).isNull();
        }
        return table;
    }

    private static List<String> guideParagraphs() throws IOException {
        String guide = Files.readString(GUIDE);
        int open = guide.indexOf(OPEN);
        assertThat(open).as("%s in %s", OPEN, GUIDE).isNotNegative();
        int body = guide.indexOf("-->", open) + "-->".length();
        int close = guide.indexOf(CLOSE, body);
        assertThat(close).as("%s after the opening marker in %s", CLOSE, GUIDE).isPositive();
        return Arrays.stream(guide.substring(body, close).split("\\n\\s*\\n"))
            .map(AttachmentsV2GuideSyncTest::normalize)
            .filter(paragraph -> !paragraph.isEmpty())
            .toList();
    }

    /** The directory holding this module's pom.xml, found by walking up from where this class was compiled to. */
    private static Path moduleRoot() {
        try {
            Path dir = Path.of(AttachmentsV2GuideSyncTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (dir != null && !Files.exists(dir.resolve("pom.xml"))) {
                dir = dir.getParent();
            }
            assertThat(dir).as("a pom.xml above %s's compiled class", AttachmentsV2GuideSyncTest.class).isNotNull();
            return dir;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A row starts lowercase, unless the javadoc's second character is a capital, as in an acronym such as API. */
    private static String startLowercase(String javadoc) {
        if (javadoc.length() > 1 && Character.isUpperCase(javadoc.charAt(1))) {
            return javadoc;
        }
        return Character.toLowerCase(javadoc.charAt(0)) + javadoc.substring(1);
    }

    /** Javadoc renders a labeled {@code {@link Target label}} as its label, so the guide carries only the label. */
    private static String javadocText(String text) {
        return normalize(text.replaceAll("\\{@code ([^}]*)}", "`$1`")
            .replaceAll("\\{@link #?[^\\s(}]+(?:\\([^)]*\\))?\\s+([^}]+)}", "`$1`")
            .replaceAll("\\{@link #?([^}]*)}", "`$1`"));
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
