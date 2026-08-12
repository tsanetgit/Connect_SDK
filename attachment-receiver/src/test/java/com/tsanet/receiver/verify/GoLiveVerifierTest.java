package com.tsanet.receiver.verify;

import com.tsanet.receiver.config.TenantConfig;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.InMemoryAttachmentStorage;
import com.tsanet.receiver.verify.VerificationReport.Check;
import com.tsanet.receiver.verify.VerificationReport.CheckResult;
import com.tsanet.receiver.verify.VerificationReport.Status;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoLiveVerifierTest {

    private static final TenantConfig STORAGE_ONLY =
            new TenantConfig("acme", "pw", "in-memory", Map.of(), Map.of());
    private static final TenantConfig WITH_CRM =
            new TenantConfig("acme", "pw", "in-memory", Map.of(), Map.of("apiToken", "t"));

    private static final StorageFactory WORKING = config -> new InMemoryAttachmentStorage();

    private static CheckResult result(VerificationReport report, Check check) {
        return report.checks().stream().filter(c -> c.check() == check).findFirst().orElseThrow();
    }

    @Test
    void passesStorageAndSkipsCrmWhenUnconfigured() {
        VerificationReport report = new GoLiveVerifier(WORKING, null).verify(STORAGE_ONLY);
        assertEquals(Status.PASS, result(report, Check.STORAGE).status());
        assertEquals(Status.SKIPPED, result(report, Check.CRM).status());
        assertTrue(report.passed(), "SKIPPED must not fail a tenant");
    }

    @Test
    void unknownBackendSurfacesAsAStorageFailureNamingIt() {
        StorageFactory unknown = config -> {
            throw new AttachmentStorageException(
                    "no storage adapter registered for backend '" + config.storageBackend() + "'");
        };
        VerificationReport report = new GoLiveVerifier(unknown, null).verify(STORAGE_ONLY);
        CheckResult storage = result(report, Check.STORAGE);
        assertEquals(Status.FAIL, storage.status());
        assertTrue(storage.message().contains("in-memory"),
                "failure must name the backend id: " + storage.message());
        assertFalse(report.passed());
    }

    @Test
    void configuredCrmWithNoProbeWiredIsALoudFailureNotASkip() {
        VerificationReport report = new GoLiveVerifier(WORKING, null).verify(WITH_CRM);
        assertEquals(Status.FAIL, result(report, Check.CRM).status());
    }

    @Test
    void bothChecksFailIndependentlyWithTheirOwnMessages() {
        StorageFactory deadStorage = config -> {
            throw new RuntimeException("storage: wrong-credential (simulated)");
        };
        CrmProbe deadCrm = props -> {
            throw new RuntimeException("crm: no-permission (simulated)");
        };
        VerificationReport report = new GoLiveVerifier(deadStorage, deadCrm).verify(WITH_CRM);
        assertEquals("storage: wrong-credential (simulated)", result(report, Check.STORAGE).message());
        assertEquals("crm: no-permission (simulated)", result(report, Check.CRM).message());
        assertFalse(report.passed());
    }

    @Test
    void crmDryRunPassesWhenTheProbeAuthenticates() {
        VerificationReport report = new GoLiveVerifier(WORKING, props -> { }).verify(WITH_CRM);
        assertEquals(Status.PASS, result(report, Check.CRM).status());
        assertTrue(report.passed());
    }
}
