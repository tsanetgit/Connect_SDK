package com.tsanet.receiver.config;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One member's receive configuration. The same shape serves both hosting modes: a
 * member-deployed receiver has a store holding one of these; the TSANet-hosted
 * multi-tenant receiver holds one per member.
 *
 * @param pathSegment       the member's routing token, used both as the tenant's URL path
 *                          segment and as its file name in the encrypted store; allowlisted
 *                          to {@code [a-z0-9][a-z0-9-]{0,63}} so it is safe as either
 * @param pushPassword      the password the platform backend presents on every push
 * @param storageBackend    which {@code AttachmentStorage} adapter this tenant uses, by
 *                          string id (e.g. {@code "s3"}); a string rather than an enum so
 *                          adapters can register without touching this record
 * @param storageProperties adapter-specific settings and credentials
 * @param crmProperties     delivery-adapter settings and credentials; empty means no CRM
 *                          delivery is configured and files stop at storage
 */
public record TenantConfig(String pathSegment, String pushPassword, String storageBackend,
                           Map<String, String> storageProperties,
                           Map<String, String> crmProperties) {

    /** URL-path-segment and filename-safe by construction: no dots, slashes, or case. */
    public static final Pattern PATH_SEGMENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public TenantConfig {
        Objects.requireNonNull(pathSegment, "pathSegment");
        if (!PATH_SEGMENT.matcher(pathSegment).matches()) {
            throw new IllegalArgumentException(
                    "pathSegment must match " + PATH_SEGMENT + ", got '" + pathSegment + "'");
        }
        if (Objects.requireNonNull(pushPassword, "pushPassword").isBlank()) {
            throw new IllegalArgumentException("pushPassword must not be blank");
        }
        if (Objects.requireNonNull(storageBackend, "storageBackend").isBlank()) {
            throw new IllegalArgumentException("storageBackend must not be blank");
        }
        storageProperties = Map.copyOf(Objects.requireNonNull(storageProperties, "storageProperties"));
        crmProperties = Map.copyOf(Objects.requireNonNull(crmProperties, "crmProperties"));
    }

    /** Whether this tenant wants received files delivered into a CRM after storage. */
    public boolean hasCrmDelivery() {
        return !crmProperties.isEmpty();
    }

    /**
     * Redacted on purpose: the password and both property maps' values are credentials.
     * Only structure (keys) is printable, so a config reaching a log line leaks nothing.
     */
    @Override
    public String toString() {
        return "TenantConfig[pathSegment=" + pathSegment
                + ", storageBackend=" + storageBackend
                + ", storageProperties=" + storageProperties.keySet()
                + ", crmProperties=" + crmProperties.keySet()
                + ", pushPassword=<redacted>]";
    }
}
