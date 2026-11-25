package com.tsanet.demo.config;

/**
 * Where one {@link EnvironmentService}'s partner key tables are seeded from when their files are
 * absent (tsanetgit/Connect_SDK#190). The source names itself, so a seed that cannot be used fails
 * startup pointing at the setting it came from.
 */
public interface PartnerSeeds {

    /** The seed JSON for environment {@code key}, or null when none is set. */
    String partnerSeed(String key);

    /** What the seed for {@code key} comes from, for messages. */
    String partnerSeedSource(String key);
}
