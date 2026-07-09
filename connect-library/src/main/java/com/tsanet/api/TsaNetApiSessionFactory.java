package com.tsanet.api;

/**
 * Creates isolated {@link TsaNetApiSession} instances that share Connect API settings but use
 * separate SQLite cache files (and therefore separate bearer tokens).
 */
public final class TsaNetApiSessionFactory {
    private final TsaNetApiConnectionSettings connectionSettings;

    public TsaNetApiSessionFactory(TsaNetApiConnectionSettings connectionSettings) {
        this.connectionSettings = connectionSettings;
    }

    public static TsaNetApiSessionFactory create(TsaNetApiConnectionSettings connectionSettings) {
        return new TsaNetApiSessionFactory(connectionSettings);
    }

    public TsaNetApiSession openSession(String sessionLabel, String username, String password) {
        TsaNetApiConfiguration configuration = TsaNetApiConfiguration.of(
            connectionSettings.apiBaseUrl(),
            sqlitePathFor(sessionLabel),
            username,
            password
        );
        return TsaNetApi.initialize(configuration);
    }

    public TsaNetApiSession openSessionForAccount(String username, String password) {
        return openSession(AccountSessionLabel.fromUsername(username), username, password);
    }

    public String sessionLabelForAccount(String username) {
        return AccountSessionLabel.fromUsername(username);
    }

    public String sqlitePathForAccount(String username) {
        return sqlitePathFor(sessionLabelForAccount(username));
    }

    public String sqlitePathForLabel(String sessionLabel) {
        return sqlitePathFor(sessionLabel);
    }

    String sqlitePathFor(String sessionLabel) {
        String sqliteBasePath = connectionSettings.sqliteBasePath();
        if (sqliteBasePath.endsWith(".db")) {
            return sqliteBasePath.substring(0, sqliteBasePath.length() - 3) + "-" + sessionLabel + ".db";
        }
        return sqliteBasePath + "-" + sessionLabel;
    }
}
