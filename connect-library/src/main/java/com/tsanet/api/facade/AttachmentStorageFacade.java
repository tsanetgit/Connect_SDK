package com.tsanet.api.facade;

import com.tsanet.api.attachments.v2.StorageConfig;
import com.tsanet.api.attachments.v2.StorageTarget;
import com.tsanet.api.attachments.v2.StorageTestResult;
import java.util.Optional;

/**
 * The receiver's side of V2 attachment delivery: where files sent to this account's company land.
 * Every call applies to the authenticated account's own company, never another. No secret is sent
 * or stored: an S3 bucket is reached through a role TSANet assumes, whose trust policy carries the
 * external id TSANet generates; an Azure Blob container is named by its tenant, storage account and
 * container.
 *
 * <p>The sender's side is {@link AttachmentsV2Facade}. Failures are
 * {@link com.tsanet.api.attachments.v2.AttachmentV2Exception}, whose {@code code()} names what
 * happened.
 */
public interface AttachmentStorageFacade {

    // PROVISIONAL(tsanetgit/Connect-API-Code#170): the platform answers 404 both for no configuration
    // and for one it doesn't show through these endpoints (today a MongoDB one), on read and on test.
    // #170's 2026-10-05 comment asks the read to tell the two apart; once it does, get() and test()
    // say which.
    /**
     * The company's current configuration, or empty when the platform answers {@code 404}: for
     * example, when nothing is registered, or when the company has a configuration this endpoint
     * doesn't show (today, a MongoDB one), which {@link #register} replaces.
     */
    Optional<StorageConfig> get();

    /**
     * Register {@code target} as the company's storage, replacing whatever was registered, of any
     * kind. The configuration starts untested ({@link StorageConfig.Verification#NEVER_TESTED}).
     * For an S3 target, give the returned {@link StorageConfig#externalId()} to the AWS account's
     * admin for the role's trust policy. A configuration the platform won't accept, or a method it
     * hasn't enabled, is refused with {@code attachment/invalid-request}.
     */
    // PROVISIONAL(tsanetgit/Connect-API-Code#170): any API user of a company can call this today.
    // When the platform moves storage changes to an admin-scoped path, this call follows it.
    StorageConfig register(StorageTarget target);

    /**
     * Ask the platform to check that it can use the registered storage. A failed check is a
     * result ({@link StorageTestResult#verified()} false, with the platform's reason), not an
     * exception; the platform records it either way. With nothing registered, or a configuration
     * this endpoint doesn't show (today, a MongoDB one), it's refused with
     * {@code attachment/not-found}.
     */
    StorageTestResult test();
}
