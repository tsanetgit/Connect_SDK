package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What {@code TSANET_DEMO_WORKSPACES} is read as, and what it is refused for
 * (tsanetgit/Connect_SDK#190). The value holds passwords, so every refusal is also checked for the
 * three secrets below, and for carrying no cause that could quote the value.
 */
@ExtendWith(OutputCaptureExtension.class)
class WorkspaceDefinitionsTest {

    private static final String GATE_PW = "s3cret-gate-pw";
    private static final String MEMBER_PW = "s3cret-member-pw";
    private static final String CLIENT_SECRET = "s3cret-client-secret";

    /** beta has no Entra tenant and audience, so only dev can take a client id and secret. */
    private static final DemoProperties PROPERTIES = new DemoProperties(
        Map.of(
            "beta", new DemoProperties.EnvironmentDef("Beta", "https://connect.example", null, null),
            "dev", new DemoProperties.EnvironmentDef("Dev", "https://connect.example", "tenant", "api://audience")),
        "beta", "unused", null, null, null, null);

    private static final String TEAM_A = "\"name\": \"team-a\", \"username\": \"team-a\", \"password\": \"" + GATE_PW + "\"";

    private static List<WorkspaceDefinitions.Definition> parse(String json) {
        return WorkspaceDefinitions.parse(json, PROPERTIES);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "[]"})
    void anUnsetOrEmptyValueConfiguresNoWorkspaces(String value) {
        assertThat(parse(value)).isEmpty();
    }

    @Test
    void aWorkspaceCarriesItsLoginItsSeedsInEitherFormAndItsPartnerSeeds() {
        var definitions = parse("[{" + TEAM_A + ","
            + " \"seeds\": {\"beta\": {\"username\": \"a@example.test\", \"password\": \"" + MEMBER_PW + "\"},"
            + "             \"dev\": {\"clientId\": \"client-a\", \"clientSecret\": \"" + CLIENT_SECRET + "\"}},"
            + " \"e2eePartners\": {\"beta\": {\"2087\": {\"jwksUrl\": \"https://keys.partner.example/jwks.json\"}}}},"
            + " {\"name\": \"team-b\", \"username\": \"team-b\", \"password\": \"b-pw\"}]");

        assertThat(definitions).extracting(WorkspaceDefinitions.Definition::name).containsExactly("team-a", "team-b");
        var teamA = definitions.get(0);
        assertThat(teamA.login().username()).isEqualTo("team-a");
        assertThat(teamA.login().password()).isEqualTo(GATE_PW);
        assertThat(teamA.login().limitedUploads()).isTrue();
        assertThat(teamA.seeds().get("beta").isOAuth()).isFalse();
        assertThat(teamA.seeds().get("beta").username()).isEqualTo("a@example.test");
        assertThat(teamA.seeds().get("beta").password()).isEqualTo(MEMBER_PW);
        assertThat(teamA.seeds().get("dev").isOAuth()).isTrue();
        assertThat(teamA.seeds().get("dev").clientId()).isEqualTo("client-a");
        assertThat(teamA.seeds().get("dev").clientSecret()).isEqualTo(CLIENT_SECRET);
        assertThat(teamA.partnerSeeds()).containsOnlyKeys("beta");
        assertThat(teamA.partnerSeeds().get("beta")).contains("\"2087\"").contains("https://keys.partner.example/jwks.json");
        assertThat(definitions.get(1).seeds()).isEmpty();
        assertThat(definitions.get(1).partnerSeeds()).isEmpty();
        assertThat(teamA.toString()).doesNotContain(GATE_PW).doesNotContain(MEMBER_PW).doesNotContain(CLIENT_SECRET);
    }

    // Each row: what follows team-a's name, username and password in its entry, then what the refusal says.
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "\"seeds\": []                                                              | workspace team-a's seeds must be an object keyed by environment",
        "\"seeds\": {\"prod\": {}}                                                  | workspace team-a's seeds names an environment that is not configured: prod",
        "\"seeds\": {\"beta\": \"a@example.test\"}                                  | workspace team-a's seeds for beta must be an object",
        "\"seeds\": {\"beta\": {\"username\": \"a@example.test\"}}                  | workspace team-a's seed for beta must be either a username and a password, or a clientId and a clientSecret",
        "\"seeds\": {\"dev\": {\"username\": \"a@example.test\", \"clientSecret\": \"s3cret-client-secret\"}} | workspace team-a's seed for dev must be either a username and a password, or a clientId and a clientSecret",
        "\"seeds\": {\"dev\": {\"username\": \"a@example.test\", \"password\": \"s3cret-member-pw\", \"clientId\": \"c\", \"clientSecret\": \"s3cret-client-secret\"}} | workspace team-a's seed for dev must be either a username and a password, or a clientId and a clientSecret",
        "\"seeds\": {\"beta\": {\"username\": \"a@example.test\", \"password\": 12345}} | workspace team-a's seed for beta must be either a username and a password, or a clientId and a clientSecret",
        "\"seeds\": {\"beta\": {\"clientId\": \"c\", \"clientSecret\": \"s3cret-client-secret\"}} | workspace team-a's seed for beta is a clientId and a clientSecret, but that environment has no Entra tenant and audience",
        "\"seeds\": {\"beta\": {\"user\": \"a@example.test\", \"password\": \"s3cret-member-pw\"}} | workspace team-a's seed for beta has a field this demo does not know: user",
        "\"e2eePartners\": {\"prod\": {}}                                           | workspace team-a's e2eePartners names an environment that is not configured: prod",
        "\"seed\": {\"beta\": {\"username\": \"a@example.test\", \"password\": \"s3cret-member-pw\"}} | entry 1 has a field this demo does not know: seed. The fields are e2eePartners, name, password, seeds, username",
    })
    void aWorkspaceThatCannotBeUsedIsRefusedSayingWhy(String rest, String why) {
        refused("[{" + TEAM_A + ", " + rest + "}]", why);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "[{\"name\": \"team-a\", \"username\": \"team-a\", \"password\": \"s3cret-gate-pw\" \"oops\"}] | is not valid JSON, or gives a field twice",
        "[{\"name\": \"team-a\", \"username\": \"team-a\", \"password\": \"s3cret-gate-pw\", \"password\": \"other\"}] | is not valid JSON, or gives a field twice",
        "[] s3cret-gate-pw                                                            | is not valid JSON, or gives a field twice",
        "{\"name\": \"team-a\", \"username\": \"team-a\", \"password\": \"s3cret-gate-pw\"} | must be a JSON list with one object per workspace",
        "[\"s3cret-gate-pw\"]                                                         | entry 1 is not an object",
        "[{\"username\": \"team-a\", \"password\": \"s3cret-gate-pw\"}]               | entry 1 needs a name of lower-case letters, digits and hyphens",
        "[{\"name\": \"Team A\", \"username\": \"team-a\", \"password\": \"s3cret-gate-pw\"}] | entry 1 needs a name of lower-case letters, digits and hyphens",
        "[{\"name\": \"../team-a\", \"username\": \"team-a\", \"password\": \"s3cret-gate-pw\"}] | entry 1 needs a name of lower-case letters, digits and hyphens",
        "[{\"name\": \"default\", \"username\": \"team-a\", \"password\": \"s3cret-gate-pw\"}] | entry 1 is named default, which is the operator's own workspace",
        "[{\"name\": \"team-a\", \"username\": \"team-a\"}]                           | workspace team-a needs a username and a password",
        "[{\"name\": \"team-a\", \"username\": \"  \", \"password\": \"s3cret-gate-pw\"}] | workspace team-a needs a username and a password",
        "[{\"name\": \"team-a\", \"username\": \"u1\", \"password\": \"s3cret-gate-pw\"}, {\"name\": \"team-a\", \"username\": \"u2\", \"password\": \"s3cret-gate-pw\"}] | names two workspaces team-a",
    })
    void aListThatCannotBeUsedIsRefusedSayingWhy(String json, String why) {
        refused(json, why);
    }

    private static void refused(String json, String why) {
        Throwable refusal = catchThrowable(() -> parse(json));

        assertThat(refusal).isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith(DemoWorkspacesProperties.VARIABLE + " ")
            .hasMessageContaining(why)
            .hasMessageNotContaining(GATE_PW)
            .hasMessageNotContaining(MEMBER_PW)
            .hasMessageNotContaining(CLIENT_SECRET)
            .hasNoCause();
    }

    @Test
    void aFieldNameThatCouldBeAMisplacedValueIsNotPrinted() {
        Throwable refusal = catchThrowable(() -> parse("[{" + TEAM_A + ", \"p@ss w0rd!\": \"x\"}]"));

        assertThat(refusal).hasMessageContaining("entry 1 has a field this demo does not know. The fields are")
            .hasMessageNotContaining("p@ss");
    }

    @Configuration
    @EnableConfigurationProperties({DemoProperties.class, DemoE2eeProperties.class, DemoAuthProperties.class,
        DemoWorkspacesProperties.class})
    @Import({E2eeKeys.class, Workspaces.class})
    static class Started {
    }

    /** A real SpringApplication start, so what Boot writes to the log about the failure is what is checked. */
    private static Throwable start(Path dataDir, String workspaces) {
        var app = new SpringApplication(Started.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return catchThrowable(() -> app.run(
            "--spring.main.banner-mode=off",
            "--tsanet.demo.data-dir=" + dataDir,
            "--tsanet.demo.auth.username=operator",
            "--tsanet.demo.auth.password=op",
            "--tsanet.demo.workspaces.definitions=" + workspaces));
    }

    @Test
    void theLoggedStartupFailureNamesTheVariableAndNeverPrintsAPassword(@TempDir Path dataDir, CapturedOutput output) {
        // The JSON parser's own message for this value quotes the text around the stray token.
        Throwable notJson = start(dataDir, "[{" + TEAM_A + " \"" + MEMBER_PW + "\"}]");
        Throwable sameUsername = start(dataDir, "[{" + TEAM_A + ", \"seeds\": {\"beta\": {\"username\": \"a@example.test\","
            + " \"password\": \"" + MEMBER_PW + "\"}}}, {\"name\": \"team-b\", \"username\": \"TEAM-A\", \"password\": \"" + GATE_PW + "\"}]");

        assertThat(notJson).hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("TSANET_DEMO_WORKSPACES is not valid JSON, or gives a field twice.");
        assertThat(sameUsername).hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("TSANET_DEMO_WORKSPACES gives workspace team-b the same username as workspace team-a"
                + " (usernames are compared without regard to case).");
        assertThat(output.getAll())
            .contains("TSANET_DEMO_WORKSPACES is not valid JSON")
            .contains("TSANET_DEMO_WORKSPACES gives workspace team-b the same username as workspace team-a")
            .doesNotContain(GATE_PW)
            .doesNotContain(MEMBER_PW);
        assertThat(dataDir.resolve("workspaces")).doesNotExist();
    }

    @Configuration
    @EnableConfigurationProperties(DemoWorkspacesProperties.class)
    static class Bound {
    }

    @Test
    void theShippedYmlWiresTheVariableVerbatim() {
        // Loads the real application.yml with the variable set as a system property, which the
        // placeholder also reads. A typo in the placeholder's name would pass every other test here.
        String value = "[{" + TEAM_A + ", \"seeds\": {\"beta\": {\"username\": \"a@example.test\", \"password\": \"ab:cd$ef#gh ij\"}}}]";
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Bound.class)
            .withSystemProperties("TSANET_DEMO_WORKSPACES=" + value)
            .run(context -> assertThat(context.getBean(DemoWorkspacesProperties.class).definitions()).isEqualTo(value));
    }

    @Test
    void anUnresolvablePlaceholderInsideTheValueIsLeftAsItIsAndFailsNothing() {
        // The RUNBOOK forbids "${" in the value, because one that resolves is rewritten. One that
        // does not resolve must not become a startup failure, which would quote the whole list.
        String value = "[{\"name\": \"team-a\", \"username\": \"team-a\", \"password\": \"" + GATE_PW + "${nope}\"}]";
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Bound.class)
            .withSystemProperties("TSANET_DEMO_WORKSPACES=" + value)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(DemoWorkspacesProperties.class).definitions()).isEqualTo(value);
            });
    }

    @Test
    void thePropertysToStringOmitsTheValue() {
        assertThat(new DemoWorkspacesProperties("[{" + TEAM_A + "}]").toString()).contains("set").doesNotContain(GATE_PW);
    }
}
