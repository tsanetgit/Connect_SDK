package com.tsanet.api.connectapi.internal;

/** The one check on a token from a token response, before anything stores or sends it. */
final class BearerTokens {
    private BearerTokens() {
    }

    /**
     * Refuses a token that isn't all visible ASCII. It goes back as a request header on every later
     * call, and the JDK's header check quotes a value it refuses, so a line break or a character
     * above Latin-1 would put the token in an exception message. Visible ASCII rather than only
     * control characters, because that check refuses more than those, and a space is never part
     * of a bearer token. Value-free, with no cause, and checked here so no caller's handling matters.
     */
    static String requireSendable(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalStateException(
                    "The token response holds an access token that can't be sent in a header");
            }
        }
        return token;
    }
}
