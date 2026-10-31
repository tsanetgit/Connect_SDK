package com.tsanet.api.connectapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.CompanyAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import com.tsanet.api.connectapi.dto.WebhookPayloadDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import com.tsanet.api.storage.WebhookSubscriptionRepository.SecretRow;
import com.tsanet.api.webhook.WebhookSignatureVerifier.SecretCandidate;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SensitiveRecordRedactionTest {
    @Test
    void storedAttachmentConfigDoesNotExposeOpaqueCredentialParameters() {
        var company = new CompanyAttachmentConfigDto(123L, Map.of("password", "sample-attachment-password"));
        var config = new AttachmentConfigDto(company, null);
        var stored = new StoredAttachmentConfigDto("sample-case-token", config);

        assertEquals(config, stored.config());
        assertEquals("sample-case-token", stored.caseToken());
        assertFalse(stored.toString().contains("sample-attachment-password"));
        assertFalse(stored.toString().contains("sample-case-token"));
    }

    @Test
    void attachmentConfigPrintsParameterNamesButNotTheirValues() {
        var company = new CompanyAttachmentConfigDto(123L, Map.of("password", "sample-attachment-password"));
        var config = new AttachmentConfigDto(company, new CompanyAttachmentConfigDto(456L, null));

        assertEquals("sample-attachment-password", company.parameters().get("password"));
        assertEquals("CompanyAttachmentConfigDto[companyId=123, parameters=[password]]", company.toString());
        assertEquals("AttachmentConfigDto[submitter=" + company
            + ", receiver=CompanyAttachmentConfigDto[companyId=456, parameters=null]]", config.toString());
    }

    static Stream<Arguments> trimmedRecords() {
        return Stream.of(
            Arguments.of(CaseNoteDto.class, List.of("id", "caseId", "type", "encrypted"), List.of("summary", "description")),
            Arguments.of(CaseResponseDto.class, List.of("id", "type", "createdAt"), List.of("nextSteps")),
            Arguments.of(CollaborationRequestStatusDto.class, List.of("id", "status", "testCase", "encrypted"),
                List.of("summary", "description")),
            Arguments.of(StoredAttachmentForwardResultDto.class, List.of("completeSuccess", "partialSuccess"),
                List.of("description", "fileName"))
        );
    }

    @ParameterizedTest
    @MethodSource("trimmedRecords")
    void trimmedRecordsKeepTheirIdentifyingFieldsAndOmitFreeText(Class<?> type, List<String> kept, List<String> omitted)
        throws Exception {
        var components = type.getRecordComponents();
        var constructor = type.getDeclaredConstructor(
            Stream.of(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        constructor.setAccessible(true);
        String diagnostic = constructor.newInstance(
            Stream.of(components).map(SensitiveRecordRedactionTest::sample).toArray()).toString();
        for (var component : components) {
            String rendered = String.valueOf(sample(component));
            if (kept.contains(component.getName())) {
                assertTrue(diagnostic.contains(component.getName() + "=" + rendered), component.getName());
            }
            if (omitted.contains(component.getName())) {
                assertFalse(diagnostic.contains(rendered), component.getName());
            }
        }
    }

    static Stream<Class<?>> records() {
        return Stream.of(
            PasswordAuthConfig.class, ClientCredentialsAuthConfig.class, OAuthAccessToken.class,
            PasswordLogin.class, SecretCandidate.class, SecretRow.class,
            WebhookSubscriptionResponseDto.class, NormalizedHttpsAttachmentConfigDto.class,
            WebhookPayloadDto.class, CaseNoteDto.class, CollaborationRequestStatusDto.class,
            CaseResponseDto.class, WebhookInboundEventDto.class, StoredAttachmentConfigDto.class,
            StoredAttachmentForwardResultDto.class
        );
    }

    @ParameterizedTest
    @MethodSource("records")
    void redactsSecretsWithoutChangingConstructorsOrAccessors(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        // Exercise compatibility constructors too: they take a prefix of the canonical fields.
        for (var constructor : type.getDeclaredConstructors()) {
            Object[] arguments = new Object[constructor.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = sample(components[i]);
            }
            constructor.setAccessible(true);
            Object record = constructor.newInstance(arguments);
            String diagnostic = record.toString();
            assertTrue(diagnostic.startsWith(type.getSimpleName() + "["), diagnostic);
            assertTrue(diagnostic.contains("<redacted>"), diagnostic);
            for (int i = 0; i < arguments.length; i++) {
                assertEquals(arguments[i], components[i].getAccessor().invoke(record), components[i].getName());
                if (sensitive(components[i].getName())) {
                    assertFalse(diagnostic.contains(String.valueOf(arguments[i])), components[i].getName());
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("records")
    void nullableSecretsAreSafeToFormat(Class<?> type) throws Exception {
        if (type == PasswordAuthConfig.class || type == ClientCredentialsAuthConfig.class
            || type == OAuthAccessToken.class) {
            return; // These constructors deliberately reject absent credentials.
        }
        var components = type.getRecordComponents();
        var constructor = type.getDeclaredConstructor(
            Stream.of(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        Object[] arguments = Stream.of(components)
            .map(component -> component.getType().isPrimitive() ? sample(component) : null).toArray();
        constructor.setAccessible(true);
        assertTrue(constructor.newInstance(arguments).toString().contains("<redacted>"));
    }

    private static Object sample(RecordComponent component) {
        Class<?> type = component.getType();
        if (type == String.class) {
            // Repeat capability identifiers inside the payload and sync message to catch indirect leaks.
            if (component.getName().equals("rawPayload") || component.getName().equals("syncMessage")) {
                return "sample-requestToken sample-noteToken";
            }
            return "sample-" + component.getName();
        }
        if (type == long.class || type == Long.class) {
            return 123L;
        }
        if (type == Integer.class) {
            return 123;
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        return null;
    }

    private static boolean sensitive(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|credential|apikey).*");
    }
}
