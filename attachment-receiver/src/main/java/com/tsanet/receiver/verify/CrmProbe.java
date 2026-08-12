package com.tsanet.receiver.verify;

import java.util.Map;

/**
 * Dry-run authentication against a tenant's CRM using its configured delivery
 * credentials. Implementations arrive with the delivery adapters (gateway-side work);
 * this module only defines the seam so go-live verification has both halves.
 */
public interface CrmProbe {

    /**
     * Proves the credentials in {@code crmProperties} can authenticate, without
     * performing any delivery.
     *
     * @throws Exception on failure, with a message specific enough to distinguish
     *                   wrong-credential from wrong-target from no-permission
     */
    void dryRun(Map<String, String> crmProperties) throws Exception;
}
