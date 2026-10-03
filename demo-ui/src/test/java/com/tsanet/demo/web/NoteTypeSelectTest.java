package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.facade.CaseNotesFacade;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * app.js can't read a Java constant, so the Add note form's Type select carries its own copy of
 * {@link CaseNotesFacade#CREATABLE_NOTE_TYPES}. This keeps the two equal, as
 * ConnectApiNotesGatewayTest keeps the constant equal to the spec.
 */
class NoteTypeSelectTest {
    private static final Pattern NOTE_FORM = Pattern.compile("['\"]note['\"]\\s*:\\s*\\{");
    private static final Pattern TYPE_SELECT = Pattern.compile(
        "\\[\\s*['\"]type['\"]\\s*,\\s*['\"][^'\"]*['\"]\\s*,\\s*['\"]select['\"]\\s*,\\s*\\[([^\\]]*)\\]\\s*\\]");
    private static final Pattern NEXT_FORM = Pattern.compile("['\"][\\w-]+['\"]\\s*:\\s*\\{");
    private static final List<String> CREATABLE = CaseNotesFacade.CREATABLE_NOTE_TYPES;

    @Test
    void theAddNoteFormOffersExactlyTheCreatableTypes() throws IOException {
        checkNoteTypeSelect(appJs());
    }

    @Test
    void theCheckRefusesAListThatIsNotTheCreatableTypes() {
        List<String> allButLast = CREATABLE.subList(0, CREATABLE.size() - 1);
        String last = CREATABLE.get(CREATABLE.size() - 1);
        for (String options : List.of(
            quoted("'", ", ", CREATABLE) + ", 'SYSTEM'",
            quoted("'", ", ", allButLast),
            quoted("'", ", ", allButLast) + ", '" + last.toLowerCase(Locale.ROOT) + "'")) {
            assertThatThrownBy(() -> checkNoteTypeSelect(noteForm(options)), options).isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void theCheckRefusesANoteFormWithoutATypeSelect() {
        String selectInTheNextForm = "const ACTION_FORMS = {\n"
            + "    'note': {\n        fields: [\n            ['summary', 'Summary', 'text'],\n        ],\n    },\n"
            + "    'other': {\n        fields: [\n            ['type', 'Type', 'select', [" + quoted("'", ", ", CREATABLE) + "]],\n        ],\n    },\n"
            + "};";

        assertThatThrownBy(() -> checkNoteTypeSelect(selectInTheNextForm)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> checkNoteTypeSelect("const ACTION_FORMS = {};")).isInstanceOf(AssertionError.class);
    }

    @Test
    void theCheckReadsDoubleQuotesAndAListOverSeveralLines() {
        assertThatCode(() -> checkNoteTypeSelect(noteForm("\n    " + quoted("\"", ",\n    ", CREATABLE.reversed()) + ",\n")))
            .doesNotThrowAnyException();
    }

    static void checkNoteTypeSelect(String js) {
        Matcher form = NOTE_FORM.matcher(js);
        assertThat(form.find()).as("app.js has no 'note' form in ACTION_FORMS").isTrue();
        Matcher select = TYPE_SELECT.matcher(js);
        assertThat(select.find(form.end())).as("app.js's 'note' form has no Type select").isTrue();
        assertThat(NEXT_FORM.matcher(js.substring(form.end(), select.start())).find())
            .as("the Type select found is in a later form, not 'note'")
            .isFalse();

        List<String> options = Arrays.stream(select.group(1).split(","))
            .map(String::strip)
            .filter(option -> !option.isEmpty())
            .map(option -> option.replaceAll("^['\"]|['\"]$", ""))
            .toList();
        assertThat(options)
            .as("app.js's Add note Type select must offer exactly CaseNotesFacade.CREATABLE_NOTE_TYPES")
            .containsExactlyInAnyOrderElementsOf(CaseNotesFacade.CREATABLE_NOTE_TYPES);
    }

    private static String appJs() throws IOException {
        try (InputStream in = NoteTypeSelectTest.class.getResourceAsStream("/static/app.js")) {
            assertThat(in).as("static/app.js is not on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String quoted(String quote, String separator, List<String> types) {
        return types.stream().map(type -> quote + type + quote).collect(Collectors.joining(separator));
    }

    private static String noteForm(String options) {
        return "const ACTION_FORMS = {\n    'note': {\n        fields: [\n"
            + "            ['type', 'Type', 'select', [" + options + "]],\n        ],\n    },\n};";
    }
}
