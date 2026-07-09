package com.tsanet.facade.config;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet")
public record ConnectFacadeProperties(
    Api api,
    Auth auth,
    Storage storage,
    List<ApplicationUserAccountConfig> accounts
) {
    public record Api(String baseUrl) {
    }

    public record Auth(String username, String password) {
        public boolean isConfigured() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }
    }

    public record Storage(String sqlitePath) {
    }

    public record ApplicationUserAccountConfig(String id, String username, String password, String sqlitePath) {
    }

    public ApplicationUserAccountRegistry toAccountRegistry() {
        if (accounts != null && !accounts.isEmpty()) {
            return ApplicationUserAccountRegistry.of(
                accounts.stream()
                    .map(account -> new ApplicationUserAccount(
                        account.id(),
                        account.username(),
                        account.password(),
                        account.sqlitePath()
                    ))
                    .toList()
            );
        }
        if (auth != null && auth.isConfigured() && storage != null && storage.sqlitePath() != null) {
            return ApplicationUserAccountRegistry.fromLegacyAuth(
                auth.username(),
                auth.password(),
                storage.sqlitePath()
            );
        }
        return ApplicationUserAccountRegistry.empty();
    }
}
