package com.tsanet.api.connectapi.internal;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

final class GatewayTestDatabaseExtension implements AfterEachCallback {
    @Override
    public void afterEach(ExtensionContext context) {
        GatewayTestSupport.cleanupCreatedDatabases();
    }
}
