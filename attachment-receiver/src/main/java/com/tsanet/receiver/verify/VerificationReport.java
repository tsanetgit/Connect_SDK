package com.tsanet.receiver.verify;

import java.util.List;

/**
 * The outcome of one tenant's go-live verification, one result per check. Failures carry
 * the underlying message verbatim: the operator reading this decides between
 * wrong-credential, wrong-target, and no-permission from it.
 */
public record VerificationReport(List<CheckResult> checks) {

    public VerificationReport {
        checks = List.copyOf(checks);
    }

    public enum Check { STORAGE, CRM }

    public enum Status { PASS, FAIL, SKIPPED }

    public record CheckResult(Check check, Status status, String message) {
    }

    /** True when nothing failed; a SKIPPED check does not fail a tenant. */
    public boolean passed() {
        return checks.stream().noneMatch(c -> c.status() == Status.FAIL);
    }
}
