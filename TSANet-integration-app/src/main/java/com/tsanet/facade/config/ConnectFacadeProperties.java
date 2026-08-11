package com.tsanet.facade.config;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountConfigMapper;
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

    public record ApplicationUserAccountConfig(
        String id,
        String sqlitePath,
        String username,
        String password,
        AccountAuthProperties auth
    ) {
        public ApplicationUserAccount toAccount() {
            if (auth != null) {
                return ApplicationUserAccountConfigMapper.fromAuthType(
                    id,
                    sqlitePath,
                    auth.type(),
                    auth.username() != null ? auth.username() : username,
                    auth.password() != null ? auth.password() : password,
                    auth.tenantId(),
                    auth.tokenUrl(),
                    auth.clientId(),
                    auth.clientSecret(),
                    auth.audience(),
                    auth.scope()
                );
            }
            return ApplicationUserAccountConfigMapper.fromLegacyFields(id, sqlitePath, username, password);
        }
    }

    public record AccountAuthProperties(
        String type,
        String username,
        String password,
        String tenantId,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String audience,
        String scope
    ) {
    }

    public ApplicationUserAccountRegistry toAccountRegistry() {
        if (accounts != null && !accounts.isEmpty()) {
            return ApplicationUserAccountRegistry.of(
                accounts.stream().map(ApplicationUserAccountConfig::toAccount).toList()
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
