package com.tsanet.demo.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.StorageConfig;
import com.tsanet.api.attachments.v2.StorageTarget;
import com.tsanet.api.attachments.v2.StorageTestResult;
import com.tsanet.demo.config.CredentialsStore;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.E2eeKeys;
import com.tsanet.demo.config.EnvironmentService;
import com.tsanet.demo.config.PartnerKeysStore;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SettingsController {

    private final EnvironmentService environments;
    private final SessionGuard guard;
    private final DemoProperties properties;

    public SettingsController(EnvironmentService environments, SessionGuard guard, DemoProperties properties) {
        this.environments = environments;
        this.guard = guard;
        this.properties = properties;
    }

    @GetMapping("/api/settings")
    public SettingsStatus getSettings() {
        List<EnvironmentStatus> envs = environments.environments().entrySet().stream()
            .map(e -> {
                var creds = environments.credentialsFor(e.getKey()).load();
                return new EnvironmentStatus(
                    e.getKey(),
                    e.getValue().label(),
                    e.getValue().apiBaseUrl(),
                    creds.isPresent(),
                    creds.map(CredentialsStore.Credentials::principal).orElse(null),
                    creds.map(CredentialsStore.Credentials::mode).orElse(null),
                    e.getValue().oauthAvailable(),
                    partnerKeyStatuses(e.getKey())
                );
            })
            .toList();
        boolean e2ee = environments.e2eeKeys().enabled();
        return new SettingsStatus(environments.activeEnvironment(), envs,
            new E2eeStatus(e2ee, e2ee ? JwksController.PATH : null));
    }

    // With E2EE off the table isn't read: the page shows nothing E2EE, as before #149.
    private List<PartnerKeyStatus> partnerKeyStatuses(String env) {
        if (!environments.e2eeKeys().enabled()) {
            return List.of();
        }
        return environments.partnerKeysFor(env).entries().entrySet().stream()
            .map(e -> new PartnerKeyStatus(e.getKey(), e.getValue().source(),
                e.getValue().jwksUrl() == null ? null : e.getValue().jwksUrl().toString(), e.getValue().kids()))
            .toList();
    }

    /**
     * Adds or replaces {@code env}'s key entry for partner {@code companyId}: a pinned JWKS URL or
     * a pasted public JWK set, exactly one. Refused before anything is parsed while E2EE is off,
     * so the demo makes no JOSE call on any path without its own keys (tsanetgit/Connect_SDK#149).
     */
    @PutMapping("/api/settings/{env}/e2ee-partners/{companyId}")
    public SettingsStatus putPartnerKeys(@PathVariable String env, @PathVariable String companyId,
                                         @RequestBody PartnerKeysBody body) {
        PartnerKeysStore store = partnerKeyStore(env);
        boolean url = !isBlank(body.jwksUrl());
        boolean jwks = !isBlank(body.jwks());
        if (url == jwks) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give exactly one of jwksUrl and jwks");
        }
        try {
            if (url) {
                URI jwksUrl = URI.create(body.jwksUrl().strip());
                // The card admits HTTPS only (#149 §1). The module also allows loopback HTTP for a local key
                // server in tests; a gate login, the guest included, must not be able to make the hosted
                // task fetch its own ports through here.
                if (!"https".equalsIgnoreCase(jwksUrl.getScheme())) {
                    throw new IllegalArgumentException("A partner JWKS URL must be https");
                }
                store.putUrl(companyId, jwksUrl);
            } else {
                store.putJwks(companyId, body.jwks());
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return getSettings();
    }

    @DeleteMapping("/api/settings/{env}/e2ee-partners/{companyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePartnerKeys(@PathVariable String env, @PathVariable String companyId) {
        partnerKeyStore(env).remove(companyId);
    }

    private PartnerKeysStore partnerKeyStore(String env) {
        if (!environments.e2eeKeys().enabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "E2EE is off for this demo: " + E2eeKeys.VARIABLE + " is not set");
        }
        if (!environments.environments().containsKey(env)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown environment: " + env);
        }
        return environments.partnerKeysFor(env);
    }

    @PostMapping("/api/settings/environment")
    public SettingsStatus switchEnvironment(@RequestBody SwitchBody body) {
        try {
            environments.switchTo(body.environment());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return getSettings();
    }

    @PostMapping("/api/settings/{env}/credentials")
    public SettingsStatus saveCredentials(@PathVariable String env, @RequestBody SaveCredentialsBody body) {
        boolean oauth = CredentialsStore.MODE_OAUTH.equals(body.mode());
        if (oauth) {
            if (isBlank(body.clientId()) || isBlank(body.clientSecret())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientId and clientSecret are required");
            }
        } else if (isBlank(body.username()) || isBlank(body.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
        }
        var def = environments.environments().get(env);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown environment: " + env);
        }
        try {
            // Build the SDK auth config before persisting. Its record constructors
            // reject values the SDK cannot use, so the operator is told at Save
            // instead of on a later request that fails for no visible reason.
            EnvironmentService.toAuthConfig(
                def,
                body.mode(),
                oauth ? body.clientId() : body.username(),
                oauth ? body.clientSecret() : body.password()
            );
            if (oauth) {
                environments.credentialsFor(env).saveOAuth(body.clientId(), body.clientSecret());
            } else {
                environments.credentialsFor(env).save(body.username(), body.password());
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        environments.invalidate(env);
        return getSettings();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @DeleteMapping("/api/settings/{env}/credentials")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCredentials(@PathVariable String env) {
        try {
            environments.credentialsFor(env).clear();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        environments.invalidate(env);
    }

    /**
     * The V2 receiver storage of the company signed in to {@code env}, through that environment's
     * own session; {@code config} is null when the platform shows none (see
     * {@link com.tsanet.api.facade.AttachmentStorageFacade#get()}).
     */
    @GetMapping("/api/settings/{env}/receiver-storage")
    public ReceiverStorageStatus getReceiverStorage(@PathVariable String env) {
        TsaNetApiSession session = sessionFor(env);
        ReceiverStorageView config = storageCall(() -> session.attachmentStorage().get())
            .map(ReceiverStorageView::of)
            .orElse(null);
        return new ReceiverStorageStatus(properties.receiverStorageEditingAllowed(), config);
    }

    /**
     * Register {@code env}'s receiver storage, replacing what's there. Refused unless
     * {@code tsanet.demo.receiver-storage-editable} is on, before any session or request: hiding
     * the page's Save button alone wouldn't stop a direct call.
     */
    @PutMapping("/api/settings/{env}/receiver-storage")
    public ReceiverStorageStatus registerReceiverStorage(@PathVariable String env, @RequestBody ReceiverStorageBody body) {
        if (!properties.receiverStorageEditingAllowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Registering receiver storage is turned off for this demo (tsanet.demo.receiver-storage-editable)");
        }
        StorageTarget target = toTarget(body);
        TsaNetApiSession session = sessionFor(env);
        StorageConfig config = storageCall(() -> session.attachmentStorage().register(target));
        return new ReceiverStorageStatus(true, ReceiverStorageView.of(config));
    }

    /** Ask the platform to check {@code env}'s registered storage; a failed check is a result, not an error. */
    @PostMapping("/api/settings/{env}/receiver-storage/test")
    public StorageTestResult testReceiverStorage(@PathVariable String env) {
        TsaNetApiSession session = sessionFor(env);
        return storageCall(() -> session.attachmentStorage().test());
    }

    private TsaNetApiSession sessionFor(String env) {
        if (!environments.environments().containsKey(env)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown environment: " + env);
        }
        return guard.session(env);
    }

    private static StorageTarget toTarget(ReceiverStorageBody body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A receiver storage configuration is required");
        }
        // Every field is stripped, the method too, so a padded value isn't stored as sent. Blank once
        // stripped is missing.
        String method = stripped(body.method());
        String prefix = stripped(body.prefix());
        try {
            return switch (method == null ? "" : method) {
                case "s3" -> new StorageTarget.S3(stripped(body.bucket()), stripped(body.region()),
                    stripped(body.roleArn()), prefix);
                case "azureBlob" -> new StorageTarget.AzureBlob(stripped(body.container()), stripped(body.tenantId()),
                    stripped(body.storageAccountName()), prefix);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "method must be s3 or azureBlob");
            };
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static String stripped(String value) {
        return isBlank(value) ? null : value.strip();
    }

    /**
     * The failure keeps its code in front of the message, as the V2 delivery screens show it. The
     * status is {@link ApiErrorHandler#upstreamStatus}, the rule for the SDK's other classified
     * failures: a failure without a platform error status (outside 400 to 599; in practice 0,
     * when the platform was unreachable or its answer unusable) is a 502.
     */
    private static <T> T storageCall(Supplier<T> call) {
        try {
            return call.get();
        } catch (AttachmentV2Exception e) {
            HttpStatusCode status = ApiErrorHandler.upstreamStatus(e.is(AttachmentV2Exception.CONNECTIVITY), e.status());
            throw new ResponseStatusException(status, e.code() + ": " + e.getMessage());
        }
    }

    public record SwitchBody(String environment) {
    }

    /** {@code method} is "s3" (bucket, region, roleArn) or "azureBlob" (container, tenantId, storageAccountName). */
    public record ReceiverStorageBody(String method, String bucket, String region, String roleArn, String container,
                                      String tenantId, String storageAccountName, String prefix) {
    }

    /** {@code editable} says whether this demo lets Settings register; {@code config} is null when the platform shows none. */
    public record ReceiverStorageStatus(boolean editable, ReceiverStorageView config) {
    }

    /** The page's flat view of a registered configuration: the fields of its method, the rest null. */
    public record ReceiverStorageView(
        String method,
        String bucket,
        String region,
        String roleArn,
        String container,
        String tenantId,
        String storageAccountName,
        String prefix,
        String externalId,
        String verification,
        OffsetDateTime lastVerifiedAt
    ) {
        static ReceiverStorageView of(StorageConfig config) {
            String verification = config.verification().name();
            return switch (config.target()) {
                case StorageTarget.S3 s3 -> new ReceiverStorageView("s3", s3.bucket(), s3.region(), s3.roleArn(),
                    null, null, null, s3.prefix(), config.externalId(), verification, config.lastVerifiedAt());
                case StorageTarget.AzureBlob blob -> new ReceiverStorageView("azureBlob", null, null, null,
                    blob.container(), blob.tenantId(), blob.storageAccountName(), blob.prefix(), null, verification,
                    config.lastVerifiedAt());
            };
        }
    }

    /** {@code mode} is "password" (default when absent) or "oauth". */
    public record SaveCredentialsBody(String mode, String username, String password,
                                      String clientId, String clientSecret) {
    }

    /** One partner's key entry: {@code source} is "url" (with {@code jwksUrl}) or "jwks" (with the pasted set's {@code kids}). */
    public record PartnerKeyStatus(String companyId, String source, String jwksUrl, List<String> kids) {
    }

    /** Exactly one of {@code jwksUrl} (HTTPS) and {@code jwks} (a public JWK set, as JSON text) is set. */
    public record PartnerKeysBody(String jwksUrl, String jwks) {
    }

    /** Whether the demo holds its own E2EE keys, and where it publishes the public ones (null when off). */
    public record E2eeStatus(boolean enabled, String jwksPath) {
    }

    public record EnvironmentStatus(
        String key,
        String label,
        String apiBaseUrl,
        boolean configured,
        String username,
        String mode,
        boolean oauthAvailable,
        // Left out when empty, so with E2EE off each environment's status is as it was.
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PartnerKeyStatus> e2eePartners
    ) {
    }

    public record SettingsStatus(String activeEnvironment, List<EnvironmentStatus> environments, E2eeStatus e2ee) {
    }
}
