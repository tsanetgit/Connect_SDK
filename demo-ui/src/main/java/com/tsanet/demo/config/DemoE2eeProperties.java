package com.tsanet.demo.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The demo's end-to-end encryption settings (tsanetgit/Connect_SDK#149). Bound as plain strings
 * and parsed in {@link E2eeKeys} and {@link PartnerKeysStore}, so a bad value fails startup with a
 * message that names the variable and never echoes the key material.
 *
 * @param privateJwks  the demo's own private JWK set (TSANET_DEMO_E2EE_PRIVATE_JWKS); blank means
 *                     E2EE is off and the demo behaves as it did without it
 * @param partnerSeeds per environment key, the JSON that seeds that environment's partner key table
 *                     when its file is absent (TSANET_DEMO_E2EE_PARTNERS_BETA and _DEV); blank means none
 */
@ConfigurationProperties(prefix = "tsanet.demo.e2ee")
public record DemoE2eeProperties(String privateJwks, Map<String, String> partnerSeeds) {

    public DemoE2eeProperties {
        if (partnerSeeds == null) {
            partnerSeeds = Map.of();
        }
    }

    /** The seed for environment {@code key}, or null when none is set. */
    public String partnerSeed(String key) {
        String seed = partnerSeeds.get(key);
        return seed == null || seed.isBlank() ? null : seed;
    }

    /** The environment variable the seed for {@code key} comes from, for messages. */
    public static String partnerSeedVariable(String key) {
        return "TSANET_DEMO_E2EE_PARTNERS_" + key.toUpperCase(java.util.Locale.ROOT);
    }

    /** Never the private set: a record's default toString would print it. */
    @Override
    public String toString() {
        return "DemoE2eeProperties[privateJwks=" + (privateJwks == null || privateJwks.isBlank() ? "unset" : "set")
            + ", partnerSeeds=" + partnerSeeds.keySet() + "]";
    }
}
