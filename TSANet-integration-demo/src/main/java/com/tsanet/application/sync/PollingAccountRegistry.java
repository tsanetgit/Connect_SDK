package com.tsanet.application.sync;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import java.util.List;

public final class PollingAccountRegistry {
    private PollingAccountRegistry() {
    }

    public static List<ApplicationUserAccount> resolve(ApplicationUserAccountRegistry accountRegistry) {
        return accountRegistry.all();
    }
}
