package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.TsaNetApiSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
            .hasMessageContaining("TEAM")
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
