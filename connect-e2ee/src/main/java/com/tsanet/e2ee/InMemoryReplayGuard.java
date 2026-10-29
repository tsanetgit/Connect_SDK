package com.tsanet.e2ee;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ReplayGuard} in this process's memory. It holds each {@code (iss, jti)} only until its
 * {@code iat} leaves the window, since anything older is refused as too old anyway, so it never
 * holds more than one window's worth.
 *
 * <p>Single process only, and forgotten on restart. A deployment with several instances, or one
 * that must not accept a replay across a restart, needs a shared, persistent implementation.
 */
public final class InMemoryReplayGuard implements ReplayGuard {
    private final Duration maxAge;
    private final Duration skew;
    private final Clock clock;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * There are no defaults: the window is the deployment's to set from the profile's guidance.
     *
     * @param maxAge how old an {@code iat} may be
     * @param skew   how far in the future an {@code iat} may be, for clock drift
     */
    public InMemoryReplayGuard(Duration maxAge, Duration skew, Clock clock) {
        if (maxAge.isNegative() || skew.isNegative()) {
            throw new IllegalArgumentException("The window can't be negative");
        }
        this.maxAge = maxAge;
        this.skew = skew;
        this.clock = clock;
    }

    @Override
    public Result check(String iss, String jti, Instant iat) {
        Instant now = clock.instant();
        if (iat.isAfter(now.plus(skew))) {
            return Result.IN_FUTURE;
        }
        if (iat.isBefore(now.minus(maxAge))) {
            return Result.TOO_OLD;
        }
        Instant expiry = iat.plus(maxAge).plus(skew);
        seen.values().removeIf(until -> until.isBefore(now));
        // A length-prefixed key, so no iss/jti pair can collide with another by where it splits.
        String key = iss.length() + ":" + iss + jti;
        return seen.putIfAbsent(key, expiry) == null ? Result.FIRST : Result.REPLAYED;
    }
}
