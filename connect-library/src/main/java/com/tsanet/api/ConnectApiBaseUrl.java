package com.tsanet.api;

/**
 * The one place an application decides whether a Connect API base URL may be used: https
 * always, plain http only when the operator opted in, in configuration, on purpose. A bearer
 * over plain http is a credential on the wire, so the check runs at startup, with the fix in
 * the message, rather than at the first request.
 *
 * <p>This is a helper applications call from their configuration; the library's own request
 * paths never invoke it, so tests and local mocks stay free to use http.
 */
public final class ConnectApiBaseUrl {

    private ConnectApiBaseUrl() {
    }

    /**
     * @param baseUrl           the configured base URL
     * @param allowInsecureHttp the operator's opt-in to plain http
     * @param baseUrlSetting    the application's name for the base URL setting, so a missing one names the fix
     * @param optOutSetting     the application's name for the opt-in, so a rejected one names the fix
     * @throws IllegalStateException when the URL is missing, or is not https and the opt-in is off
     */
    public static void requireHttps(String baseUrl, boolean allowInsecureHttp, String baseUrlSetting, String optOutSetting) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(baseUrlSetting + " is required");
        }
        if (baseUrl.regionMatches(true, 0, "https://", 0, 8)) {
            return;
        }
        if (allowInsecureHttp) {
            return;
        }
        throw new IllegalStateException("Connect API base URL must use https; the Connect API is HTTPS-only in every "
            + "real environment and a bearer token must not travel in plain text. Set " + optOutSetting
            + "=true only for a local mock; it admits any non-https host.");
    }
}
