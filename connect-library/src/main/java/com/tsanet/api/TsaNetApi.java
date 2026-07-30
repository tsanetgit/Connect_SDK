package com.tsanet.api;

import com.tsanet.api.internal.TsaNetApiRuntime;

public final class TsaNetApi {
    private TsaNetApi() {
    }

    public static TsaNetApiSession initialize(TsaNetApiConfiguration configuration) {
        return TsaNetApiRuntime.create(configuration);
    }

    public static TsaNetApiSessionFactory sessionFactory(TsaNetApiConnectionSettings connectionSettings) {
        return TsaNetApiSessionFactory.create(connectionSettings);
    }
}
