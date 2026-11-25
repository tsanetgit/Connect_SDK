package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.demo.E2eeTestVectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Which workspace a request gets (tsanetgit/Connect_SDK#190), with the request's login set on the
 * thread the way the gate leaves it. Offline: sessions are built over a temp dir and never
 * authenticate, and nothing listens on the apiBaseUrl below.
 */
class WorkspacesTest {

    private static final CredentialsStore.Credentials MEMBER =
        CredentialsStore.Credentials.password("user@example.com", "member-pw");

    @TempDir
    Path dataDir;

    @AfterEach
    void clearTheLogin() {
        SecurityContextHolder.clearContext();
    }

    private DemoProperties properties() {
        return new DemoProperties(
            Map.of(
                "beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null),
                "dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString(),
            true,  // nothing listens on the test URL
            null,
            null,  // credentials not locked
            null  // no seeds
        );
    }

    /** What a deploy with these gate settings builds. */
    private Workspaces deployed(String password, String guestPassword) {
        return new Workspaces(properties(), E2eeKeys.disabled(), new DemoE2eeProperties(null, null),
            new DemoAuthProperties("operator", password, new DemoAuthProperties.Guest("visitor", guestPassword, null)));
    }

    private static final String TEAM_A = "{\"name\": \"team-a\", \"username\": \"Team-A\", \"password\": \"s3cret-a-pw\"}";

    /** What a deploy with the operator and guest logins and this TSANET_DEMO_WORKSPACES value builds. */
    private Workspaces configured(String workspaces) {
        return new Workspaces(properties(), E2eeKeys.disabled(), new DemoE2eeProperties(null, null),
            new DemoAuthProperties("operator", "op", new DemoAuthProperties.Guest("visitor", "g", null)),
            new DemoWorkspacesProperties(workspaces));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "[]"})
    void withNoWorkspacesConfiguredThereIsOnlyTheDefaultOne(String workspaces) {
        assertThat(configured(workspaces).all()).extracting(Workspaces.Workspace::name).containsExactly(Workspaces.DEFAULT);
        assertThat(dataDir.resolve("workspaces")).doesNotExist();
    }

    @Test
    void aConfiguredWorkspaceHasItsOwnLoginAndKeepsItsStateInItsOwnDirectory() {
        Workspaces workspaces = configured("[" + TEAM_A + "]");
        assertThat(workspaces.all()).extracting(Workspaces.Workspace::name).containsExactly(Workspaces.DEFAULT, "team-a");

        signedInAs("Team-A");
        assertThat(workspaces.current().name()).isEqualTo("team-a");
        assertThat(workspaces.current().logins()).singleElement().satisfies(login -> assertThat(login.limitedUploads()).isTrue());
        workspaces.current().environments().switchTo("dev");

        assertThat(dataDir.resolve("workspaces/team-a/active-environment")).hasContent("dev");
        assertThat(dataDir.resolve("active-environment")).doesNotExist();
        signedInAs("operator");
        assertThat(workspaces.current().name()).isEqualTo(Workspaces.DEFAULT);
        assertThat(workspaces.current().environments().activeEnvironment()).isEqualTo("beta");
    }

    @Test
    void workspacesWithTheGateOffAreRefused() {
        assertThatThrownBy(() -> new Workspaces(properties(), E2eeKeys.disabled(), new DemoE2eeProperties(null, null),
                new DemoAuthProperties(null, "", null), new DemoWorkspacesProperties("[" + TEAM_A + "]")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TSANET_DEMO_WORKSPACES is set but TSANET_DEMO_AUTH_PASSWORD is not")
            .hasMessageNotContaining("s3cret-a-pw");
        assertThat(dataDir.resolve("workspaces")).doesNotExist();
    }

    // The username already taken, by whom, and what the refusal calls that login.
    @ParameterizedTest
    @CsvSource({
        "OPERATOR, the operator login (TSANET_DEMO_AUTH_USER)",
        "Visitor, the guest login (TSANET_DEMO_AUTH_GUEST_USER)",
        "TEAM-a, workspace team-a",
    })
    void aUsernameAnotherLoginHasIsRefusedNamingTheLoginsAndNotTheUsername(String username, String owner) {
        String teamB = "{\"name\": \"team-b\", \"username\": \"" + username + "\", \"password\": \"s3cret-b-pw\"}";

        assertThatThrownBy(() -> configured("[" + TEAM_A + ", " + teamB + "]"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gives workspace team-b the same username as " + owner)
            .hasMessageNotContaining(username)
            .hasMessageNotContaining("s3cret-a-pw")
            .hasMessageNotContaining("s3cret-b-pw");
        assertThat(dataDir.resolve("workspaces")).doesNotExist();
    }

    @Test
    void aPartnerSeedThatCannotBeUsedNamesItsWorkspaceAndClosesWhatWasAlreadyBuilt() throws Exception {
        java.util.Set<Long> before = selectorThreads();
        E2eeKeys keys = E2eeKeys.fromJson(E2eeTestVectors.load().memberAPrivateJwks());
        // In this order, so team-b's beta table is read, with its key fetcher, before its dev seed is refused.
        Map<String, DemoProperties.EnvironmentDef> betaThenDev = new java.util.LinkedHashMap<>();
        betaThenDev.put("beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null));
        betaThenDev.put("dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null));
        DemoProperties ordered = new DemoProperties(betaThenDev, "beta", dataDir.toString(), true, null, null, null);
        String teamB = "{\"name\": \"team-b\", \"username\": \"team-b\", \"password\": \"s3cret-b-pw\","
            + " \"e2eePartners\": {\"beta\": {}, \"dev\": {\"not-a-company-id\": {}}}}";

        // With keys loaded each workspace's partner tables are read as its state is built: the default
        // workspace's and team-a's are built, and half of team-b's, before team-b's dev seed is refused.
        assertThatThrownBy(() -> new Workspaces(ordered, keys, new DemoE2eeProperties(null, null),
                new DemoAuthProperties("operator", "op", null), new DemoWorkspacesProperties("[" + TEAM_A + ", " + teamB + "]")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TSANET_DEMO_WORKSPACES (workspace team-b, e2eePartners for dev) is not a usable partner key table")
            .hasMessageNotContaining("s3cret");

        java.util.Set<Long> left = selectorThreads();
        left.removeAll(before);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!left.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            left.retainAll(selectorThreads());
        }
        assertThat(left).isEmpty();
    }

    private static java.util.Set<Long> selectorThreads() {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().contains("SelectorManager")) {
                ids.add(thread.threadId());
            }
        }
        return ids;
    }

    /** A workspace with one login, its state under its own directory of the data dir. */
    private Workspaces.Workspace workspace(String name, String username, String password) {
        var environments = new EnvironmentService(properties(), E2eeKeys.disabled(), new DemoE2eeProperties(null, null),
            dataDir.resolve(name));
        return new Workspaces.Workspace(name, environments, List.of(new Workspaces.Login(username, password, true)), Map.of());
    }

    private static void signedInAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, AuthorityUtils.createAuthorityList("ROLE_DEMO")));
    }

    private static void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }

    @Test
    void theOperatorAndTheGuestOpenTheDefaultWorkspaceUnderTheDataDirItself() throws Exception {
        Workspaces workspaces = deployed("op", "g");
        assertThat(workspaces.all()).extracting(Workspaces.Workspace::name).containsExactly(Workspaces.DEFAULT);

        signedInAs("visitor");
        workspaces.current().environments().switchTo("dev");

        signedInAs("operator");
        assertThat(workspaces.current().environments().activeEnvironment()).isEqualTo("dev");
        assertThat(Files.readString(dataDir.resolve("active-environment"))).isEqualTo("dev");
    }

    @Test
    void withTheGateOffEveryRequestGetsTheDefaultWorkspace() {
        Workspaces workspaces = deployed("", "");

        assertThat(workspaces.current().name()).isEqualTo(Workspaces.DEFAULT);
        assertThat(workspaces.current().logins()).isEmpty();
        anonymous();
        assertThat(workspaces.current()).isSameAs(workspaces.all().get(0));
    }

    @Test
    void eachLoginOpensItsOwnWorkspaceAndNothingOfTheOthers() throws Exception {
        Workspaces workspaces = new Workspaces(
            List.of(workspace("team-a", "team-a", "a-pw"), workspace("team-b", "team-b", "b-pw")), true);

        signedInAs("team-a");
        assertThat(workspaces.current().name()).isEqualTo("team-a");
        workspaces.current().environments().switchTo("dev");
        workspaces.current().environments().credentialsFor("beta").save("user@example.com", "member-pw");

        signedInAs("team-b");
        assertThat(workspaces.current().name()).isEqualTo("team-b");
        assertThat(workspaces.current().environments().activeEnvironment()).isEqualTo("beta");
        assertThat(workspaces.current().environments().credentialsFor("beta").load()).isEmpty();

        assertThat(dataDir.resolve("team-a/active-environment")).hasContent("dev");
        assertThat(dataDir.resolve("team-a/credentials-beta.properties")).exists();
        assertThat(dataDir.resolve("team-b")).doesNotExist();
    }

    @Test
    void aLoginIsMatchedWhateverItsCase() {
        Workspaces workspaces = new Workspaces(
            List.of(workspace("team-a", "Team-A", "a-pw"), workspace("team-b", "team-b", "b-pw")), true);

        signedInAs("Team-A");
        assertThat(workspaces.current().name()).isEqualTo("team-a");
        signedInAs("team-a");
        assertThat(workspaces.current().name()).isEqualTo("team-a");
    }

    @Test
    void withTheGateOnARequestWithoutAWorkspaceLoginFailsAndIsNotGivenTheDefault() {
        // The second workspace's login is named like Spring's anonymous user, the name a name-only lookup would match.
        Workspaces workspaces = new Workspaces(
            List.of(workspace("team-a", "team-a", "a-pw"), workspace("team-b", "anonymousUser", "b-pw")), true);

        assertThatThrownBy(workspaces::current).isInstanceOf(IllegalStateException.class);  // no login at all
        anonymous();
        assertThatThrownBy(workspaces::current).isInstanceOf(IllegalStateException.class);
        signedInAs("someone-else");
        assertThatThrownBy(workspaces::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoLoginsUnderOneNameAreRefusedWithoutPrintingAPassword() {
        List<Workspaces.Workspace> clash = List.of(workspace("team-a", "team", "s3cret-a-pw"), workspace("team-b", "TEAM", "s3cret-b-pw"));

        assertThatThrownBy(() -> new Workspaces(clash, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Two gate logins share a username")
            .hasMessageNotContaining("s3cret-a-pw")
            .hasMessageNotContaining("s3cret-b-pw");
    }

    @Test
    void aLoginsToStringOmitsThePassword() {
        assertThat(workspace("team-a", "team-a", "s3cret-a-pw").toString())
            .contains("team-a").doesNotContain("s3cret-a-pw");
    }

    @Test
    void closingClosesEveryWorkspacesSessions() {
        Workspaces workspaces = new Workspaces(
            List.of(workspace("team-a", "team-a", "a-pw"), workspace("team-b", "team-b", "b-pw")), true);
        TsaNetApiSession first = workspaces.all().get(0).environments().sessionFor("beta", MEMBER);
        TsaNetApiSession second = workspaces.all().get(1).environments().sessionFor("beta", MEMBER);

        workspaces.close();

        assertThat(loginFailure(first)).hasRootCauseMessage("closed");
        assertThat(loginFailure(second)).hasRootCauseMessage("closed");
    }

    @Test
    void itClosesItsWorkspacesSessionsWhenItsContextShutsDown() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(Workspaces.class, () -> deployed("", ""));
        context.refresh();
        TsaNetApiSession session = context.getBean(Workspaces.class).current().environments().sessionFor("beta", MEMBER);

        context.close();

        assertThat(loginFailure(session)).hasRootCauseMessage("closed");
    }

    // Nothing listens on the test URL: an open client is refused there, and a closed one says closed.
    private static Throwable loginFailure(TsaNetApiSession session) {
        return org.assertj.core.api.Assertions.catchThrowable(() -> session.auth().login("user", "password"));
    }
}
