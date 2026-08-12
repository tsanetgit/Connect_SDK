package com.tsanet.receiver.config;

import java.util.List;
import java.util.Optional;

/**
 * Where tenant configurations live. A member-deployed receiver has one tenant; the
 * TSANet-hosted receiver has many. Implementations hold credentials, so at-rest
 * encryption is part of the store's contract, not the caller's problem.
 */
public interface TenantConfigStore {

    /**
     * The tenant routed by this path segment, or empty when none is configured.
     *
     * @throws IllegalArgumentException  when the segment is not a valid routing token
     *                                   (defense on the read path: a raw request value
     *                                   must not be able to address arbitrary files)
     * @throws TenantConfigException     when the tenant exists but cannot be loaded
     *                                   (corruption, tampering, wrong key)
     */
    Optional<TenantConfig> byPathSegment(String pathSegment) throws TenantConfigException;

    /** Every configured tenant. */
    List<TenantConfig> all() throws TenantConfigException;

    /** Creates or replaces the tenant's configuration, atomically. */
    void save(TenantConfig config) throws TenantConfigException;
}
