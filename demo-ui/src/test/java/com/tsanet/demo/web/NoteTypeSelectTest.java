package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Add note form's Type select comes from the server (tsanetgit/Connect-API-Code#200): its
 * options are the {@code noteTypeOptions} function, which puts a blank "No type" first, so it is
 * the default, and then loads {@code /api/note-types}. A hard-coded list of codes would go stale
 * when the server adds a type, and a typed default would type every note sent from the form.
 */
class NoteTypeSelectTest {
    private static final Pattern NOTE_FORM = Pattern.compile("['\"]note['\"]\\s*:\\s*\\{");
    private static final Pattern TYPE_SELECT = Pattern.compile(
        "\\[\\s*['\"]type['\"]\\s*,\\s*['\"][^'\"]*['\"]\\s*,\\s*['\"]select['\"]\\s*,\\s*([^\\]]*?)\\s*\\]");
    private static final Pattern NEXT_FORM = Pattern.compile("['\"][\\w-]+['\"]\\s*:\\s*\\{");
    private static final Pattern OPTIONS_FUNCTION = Pattern.compile(
        "async function noteTypeOptions\\(\\)\\s*\\{(.*?)\\n\\}", Pattern.DOTALL);
    private static final Pattern NO_TYPE_FIRST = Pattern.compile(
        "const none = \\{value: '', label: 'No type'\\};.*return \\[none, \\.\\.\\.", Pattern.DOTALL);

    @Test
    void theAddNoteFormLoadsItsTypesWithNoTypeFirst() throws IOException {
        checkNoteTypeSelect(appJs());
    }

    @Test
    void appJsNamesNoTypeFromBeforeNoteTypesWereReferenceData() throws IOException {
        assertThat(appJs()).doesNotContain("USER_PARTNER", "USER_PUBLIC");
    }

    @Test
    void theCheckRefusesAHardCodedList() {
        for (String options : List.of("['USER_PARTNER', 'USER_PUBLIC']", "['partner', 'public']", "[]")) {
            assertThatThrownBy(() -> checkNoteTypeSelect(noteForm(options) + OPTIONS_OK), options)
                .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void theCheckRefusesOptionsWithoutABlankNoTypeFirst() {
        for (String body : List.of(
            "    const none = {value: 'partner', label: 'No type'};\n    return [none, ...types];\n",
            "    const none = {value: '', label: 'No type'};\n    return [...types, none];\n",
            "    return types;\n")) {
            String js = noteForm("noteTypeOptions") + "\nasync function noteTypeOptions() {\n"
                + "    const types = await fetchJson('/api/note-types');\n" + body + "}\n";
            assertThatThrownBy(() -> checkNoteTypeSelect(js), body).isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void theCheckRefusesOptionsThatDoNotAskTheServer() {
        String js = noteForm("noteTypeOptions") + "\nasync function noteTypeOptions() {\n"
            + "    const none = {value: '', label: 'No type'};\n    return [none, ...['partner']];\n}\n";

        assertThatThrownBy(() -> checkNoteTypeSelect(js)).isInstanceOf(AssertionError.class);
    }

    @Test
    void theCheckRefusesANoteFormWithoutATypeSelect() {
        String selectInTheNextForm = "const ACTION_FORMS = {\n"
            + "    'note': {\n        fields: [\n            ['summary', 'Summary', 'text'],\n        ],\n    },\n"
            + "    'other': {\n        fields: [\n            ['type', 'Type', 'select', noteTypeOptions],\n        ],\n    },\n"
            + "};" + OPTIONS_OK;

        assertThatThrownBy(() -> checkNoteTypeSelect(selectInTheNextForm)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> checkNoteTypeSelect("const ACTION_FORMS = {};" + OPTIONS_OK))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void theCheckAcceptsTheIntendedShape() {
        assertThatCode(() -> checkNoteTypeSelect(noteForm("noteTypeOptions") + OPTIONS_OK)).doesNotThrowAnyException();
    }

    private static final String OPTIONS_OK = "\nasync function noteTypeOptions() {\n"
        + "    const none = {value: '', label: 'No type'};\n"
        + "    const types = await fetchJson('/api/note-types');\n"
        + "    return [none, ...types.map(t => ({value: t.code, label: t.displayName}))];\n}\n";

    static void checkNoteTypeSelect(String js) {
        Matcher form = NOTE_FORM.matcher(js);
        assertThat(form.find()).as("app.js has no 'note' form in ACTION_FORMS").isTrue();
        Matcher select = TYPE_SELECT.matcher(js);
        assertThat(select.find(form.end())).as("app.js's 'note' form has no Type select").isTrue();
        assertThat(NEXT_FORM.matcher(js.substring(form.end(), select.start())).find())
            .as("the Type select found is in a later form, not 'note'")
            .isFalse();
        assertThat(select.group(1))
            .as("the Type select's options must be the noteTypeOptions function, not a hard-coded list")
            .isEqualTo("noteTypeOptions");

        Matcher function = OPTIONS_FUNCTION.matcher(js);
        assertThat(function.find()).as("app.js has no noteTypeOptions function").isTrue();
        String body = function.group(1);
        assertThat(body).as("noteTypeOptions must load the server's list").contains("fetchJson('/api/note-types')");
        assertThat(NO_TYPE_FIRST.matcher(body).find())
            .as("noteTypeOptions must return a blank 'No type' option first, so it is the default")
            .isTrue();
    }

    private static String appJs() throws IOException {
        try (InputStream in = NoteTypeSelectTest.class.getResourceAsStream("/static/app.js")) {
            assertThat(in).as("static/app.js is not on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String noteForm(String options) {
        return "const ACTION_FORMS = {\n    'note': {\n        fields: [\n"
            + "            ['type', 'Type', 'select', " + options + "],\n        ],\n    },\n};";
    }
}
