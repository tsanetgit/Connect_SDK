package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import com.tsanet.api.connectapi.dto.WebhookPayloadDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import com.tsanet.api.connectapi.internal.PasswordLogin;
import com.tsanet.api.storage.WebhookSubscriptionRepository.SecretRow;
import com.tsanet.api.webhook.WebhookSignatureVerifier.SecretCandidate;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RecordRedactionTest {
    static Stream<Class<? extends Record>> sensitiveRecords() {
        return Stream.of(
            PasswordAuthConfig.class, ClientCredentialsAuthConfig.class, OAuthAccessToken.class,
            PasswordLogin.class, SecretCandidate.class, SecretRow.class,
            WebhookSubscriptionResponseDto.class, NormalizedHttpsAttachmentConfigDto.class,
            CaseNoteDto.class, CaseResponseDto.class, CollaborationRequestStatusDto.class,
            StoredAttachmentConfigDto.class, StoredAttachmentForwardResultDto.class,
            WebhookInboundEventDto.class, WebhookPayloadDto.class
        );
    }

    @ParameterizedTest
    @MethodSource("sensitiveRecords")
    void redactsEntireDiagnosticWithoutChangingAccessors(Class<? extends Record> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = Arrays.stream(components).map(component -> fixtureValue(type, component)).toArray();
        Record record = type.getDeclaredConstructor(types).newInstance(values);

        assertThat(record.toString()).isEqualTo(type.getSimpleName() + "[<redacted>]");
        for (int i = 0; i < components.length; i++) {
            assertThat(components[i].getAccessor().invoke(record))
                .as(type.getSimpleName() + "." + components[i].getName()).isEqualTo(values[i]);
            if (values[i] instanceof String sentinel) {
                assertThat(record.toString()).doesNotContain(sentinel);
            }
        }
    }

    private static Object fixtureValue(Class<?> recordType, RecordComponent component) {
        Class<?> type = component.getType();
        if (type == String.class) {
            String sentinel = "synthetic-" + recordType.getSimpleName() + "-" + component.getName() + "-private";
            return component.getName().toLowerCase(java.util.Locale.ROOT).contains("url")
                ? "https://user:" + sentinel + "@example.invalid/path?token=" + sentinel
                : sentinel;
        }
        if (type == Long.class || type == long.class) {
            return 3600L;
        }
        if (type == Integer.class) {
            return 443;
        }
        if (type == Boolean.class || type == boolean.class) {
            return true;
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == AttachmentConfigDto.class) {
            return new AttachmentConfigDto(null, null);
        }
        throw new IllegalArgumentException("Missing fixture for " + type.getName());
    }
}
