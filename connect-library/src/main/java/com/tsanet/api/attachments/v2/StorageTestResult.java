package com.tsanet.api.attachments.v2;

import java.time.OffsetDateTime;

/**
 * One test of a receiving company's storage configuration, mapped from the generated
 * {@code StorageConfigTestResultDTO}. A failed test is a result, not an error: the platform
 * answers it with {@code 200} and records it as the configuration's latest verification.
 *
 * @param verified   true when the platform could use the storage as configured
 * @param verifiedAt when the test ran
 * @param detail     the platform's reason when {@code verified} is false; otherwise usually null
 */
public record StorageTestResult(boolean verified, OffsetDateTime verifiedAt, String detail) {
}
