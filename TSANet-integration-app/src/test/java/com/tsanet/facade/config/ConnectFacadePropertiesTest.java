package com.tsanet.facade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * An account's {@code allowed-receiver-company-ids}, bound as the console binds it, reaches its
 * ApplicationUserAccount, and an account without one is named at startup.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConnectFacadePropertiesTest {

    private static ConnectFacadeProperties properties(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bind("tsanet", ConnectFacadeProperties.class)
            .get();
    }

    private static List<ConnectFacadeProperties.ApplicationUserAccountConfig> accounts(Map<String, String> properties) {
        return properties(properties).accounts();
    }

    @Test
    void eachAccountsReceiverAllowlistReachesItsAccountOnBothMapperBranches() {
        List<ConnectFacadeProperties.ApplicationUserAccountConfig> accounts = accounts(Map.ofEntries(
            Map.entry("tsanet.accounts[0].id", "legacy"),
            Map.entry("tsanet.accounts[0].sqlite-path", "target/legacy.db"),
            Map.entry("tsanet.accounts[0].username", "legacy@example.test"),
            Map.entry("tsanet.accounts[0].password", "pw"),
            Map.entry("tsanet.accounts[0].allowed-receiver-company-ids", "101,202"),
            Map.entry("tsanet.accounts[1].id", "typed"),
            Map.entry("tsanet.accounts[1].sqlite-path", "target/typed.db"),
            Map.entry("tsanet.accounts[1].auth.type", "password"),
            Map.entry("tsanet.accounts[1].auth.username", "typed@example.test"),
            Map.entry("tsanet.accounts[1].auth.password", "pw"),
            Map.entry("tsanet.accounts[1].allowed-receiver-company-ids[0]", "303"),
            Map.entry("tsanet.accounts[2].id", "open"),
            Map.entry("tsanet.accounts[2].sqlite-path", "target/open.db"),
            Map.entry("tsanet.accounts[2].username", "open@example.test"),
            Map.entry("tsanet.accounts[2].password", "pw")
        ));

        assertThat(accounts.get(0).toAccount().allowedReceiverCompanyIds()).as("legacy fields")
            .containsExactlyInAnyOrder(101L, 202L);
        assertThat(accounts.get(1).toAccount().allowedReceiverCompanyIds()).as("auth block")
            .containsExactly(303L);
        assertThat(accounts.get(2).toAccount().allowedReceiverCompanyIds()).as("no list").isEmpty();
    }

    @Test
    void anAccountWithoutAReceiverAllowlistIsNamedAtStartup(CapturedOutput output) {
        // Through the bean method Spring calls at startup, so the test fails if the bean stops logging.
        ApplicationUserAccountRegistry registry = new ConnectFacadeConfiguration().applicationUserAccountRegistry(
            properties(Map.ofEntries(
                Map.entry("tsanet.accounts[0].id", "open"),
                Map.entry("tsanet.accounts[0].sqlite-path", "target/open.db"),
                Map.entry("tsanet.accounts[0].username", "open@example.test"),
                Map.entry("tsanet.accounts[0].password", "pw"),
                Map.entry("tsanet.accounts[1].id", "listed"),
                Map.entry("tsanet.accounts[1].sqlite-path", "target/listed.db"),
                Map.entry("tsanet.accounts[1].username", "listed@example.test"),
                Map.entry("tsanet.accounts[1].password", "pw"),
                Map.entry("tsanet.accounts[1].allowed-receiver-company-ids", "101")
            )));

        assertThat(registry.all()).extracting(ApplicationUserAccount::id).containsExactlyInAnyOrder("open", "listed");
        assertThat(output.getOut()).contains("Account open has no V2 receiver allowlist")
            .doesNotContain("Account listed");
    }
}
