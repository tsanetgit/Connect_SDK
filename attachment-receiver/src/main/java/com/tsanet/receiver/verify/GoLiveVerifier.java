package com.tsanet.receiver.verify;

import com.tsanet.receiver.config.TenantConfig;
import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.verify.VerificationReport.Check;
import com.tsanet.receiver.verify.VerificationReport.CheckResult;
import com.tsanet.receiver.verify.VerificationReport.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a tenant's go-live checks: build the configured storage and prove it with the
 * SPI's write-read-delete probe; dry-run the CRM credentials when delivery is
 * configured. Run on configuration save and on demand; a passing report is the
 * precondition for registering the tenant's receive config with the platform.
 *
 * <p>Each check is isolated: an unexpected RuntimeException from an adapter lands as
 * that check's FAIL, never as an aborted report.
 */
public final class GoLiveVerifier {

    private final StorageFactory storageFactory;
    private final CrmProbe crmProbe;

    /** {@code crmProbe} may be null while no delivery adapter is wired in. */
    public GoLiveVerifier(StorageFactory storageFactory, CrmProbe crmProbe) {
        this.storageFactory = storageFactory;
        this.crmProbe = crmProbe;
    }

    public VerificationReport verify(TenantConfig config) {
        List<CheckResult> results = new ArrayList<>();
        results.add(storageCheck(config));
        results.add(crmCheck(config));
        return new VerificationReport(results);
    }

    private CheckResult storageCheck(TenantConfig config) {
        try {
            AttachmentStorage storage = storageFactory.create(config);
            storage.verifyAccess();
            return new CheckResult(Check.STORAGE, Status.PASS,
                    "backend '" + config.storageBackend() + "' passed the write-read-delete probe");
        } catch (Exception e) {
            return new CheckResult(Check.STORAGE, Status.FAIL, failureMessage(e));
        }
    }

    private CheckResult crmCheck(TenantConfig config) {
        if (!config.hasCrmDelivery()) {
            return new CheckResult(Check.CRM, Status.SKIPPED, "no CRM delivery configured");
        }
        if (crmProbe == null) {
            return new CheckResult(Check.CRM, Status.FAIL,
                    "CRM delivery is configured but no probe is available to verify it");
        }
        try {
            crmProbe.dryRun(config.crmProperties());
            return new CheckResult(Check.CRM, Status.PASS, "CRM credentials authenticated");
        } catch (Exception e) {
            return new CheckResult(Check.CRM, Status.FAIL, failureMessage(e));
        }
    }

    private static String failureMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
