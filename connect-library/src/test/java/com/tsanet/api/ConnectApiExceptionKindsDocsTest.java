package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The README's error table and the integration guide each list {@link ConnectApiException.Kind}'s
 * values, and nothing else keeps them in step with the enum (tsanetgit/Connect_SDK#131). Only the
 * names are checked, not the prose around them: a kind added to the enum, or dropped from one
 * list, fails here.
 */
class ConnectApiExceptionKindsDocsTest {

    private static final Path MODULE = moduleRoot();
    private static final Path README = MODULE.resolve("README.md");
    private static final Path GUIDE =
        MODULE.resolve("../skills/connect-sdk-deploy/references/consume-artifact.md").normalize();
    /** "`kind()` is `PROBLEM`, ... or `OTHER`, with the API's own", across line breaks. */
    private static final Pattern GUIDE_KIND_LIST = Pattern.compile("`kind\\(\\)` is (.*?), with the API's own", Pattern.DOTALL);

    @Test
    void theReadmeKindRowNamesEveryKind() throws IOException {
        String row = Files.readAllLines(README).stream()
            .filter(line -> line.startsWith("| `kind()` |"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no `kind()` row in " + README));

        assertNamesEveryKind(row, "README `kind()` row");
    }

    @Test
    void everyKindListInTheGuideNamesEveryKind() throws IOException {
        Matcher lists = GUIDE_KIND_LIST.matcher(Files.readString(GUIDE));
        List<String> found = new ArrayList<>();
        while (lists.find()) {
            found.add(lists.group(1).replaceAll("\\s+", " "));
        }

        assertThat(found).as("`kind()` lists in %s", GUIDE).isNotEmpty();
        found.forEach(list -> assertNamesEveryKind(list, "guide list \"" + list + "\""));
    }

    private static void assertNamesEveryKind(String text, String where) {
        for (ConnectApiException.Kind kind : ConnectApiException.Kind.values()) {
            assertThat(text).as("%s names %s", where, kind).contains("`" + kind.name() + "`");
        }
    }

    private static Path moduleRoot() {
        try {
            Path dir = Path.of(ConnectApiExceptionKindsDocsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (dir != null && !Files.exists(dir.resolve("pom.xml"))) {
                dir = dir.getParent();
            }
            assertThat(dir).as("a pom.xml above %s's compiled class", ConnectApiExceptionKindsDocsTest.class).isNotNull();
            return dir;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
