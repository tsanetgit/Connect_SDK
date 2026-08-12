package com.tsanet.receiver.verify;

import com.tsanet.receiver.config.TenantConfig;
import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;

/**
 * Builds the {@link AttachmentStorage} a tenant's configuration selects. Adapters
 * register here by string id ({@code TenantConfig#storageBackend()}); an unknown id
 * throws with a message naming it, which the verifier surfaces as a STORAGE failure.
 *
 * <p>{@code create} may construct lazily; it does not have to prove connectivity. The
 * go-live verifier proves connectivity itself by calling
 * {@link AttachmentStorage#verifyAccess()} on what this returns.
 */
public interface StorageFactory {

    AttachmentStorage create(TenantConfig config) throws AttachmentStorageException;
}
