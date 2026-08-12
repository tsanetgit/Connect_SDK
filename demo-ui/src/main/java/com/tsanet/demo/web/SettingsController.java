package com.tsanet.demo.web;

import com.tsanet.demo.config.CredentialsStore;
import com.tsanet.demo.config.EnvironmentService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SettingsController {

    private final EnvironmentService environments;

    public SettingsController(EnvironmentService environments) {
        this.environments = environments;
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
                    e.getValue().oauthAvailable()
                );
            })
            .toList();
        return new SettingsStatus(environments.activeEnvironment(), envs);
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

    public record SwitchBody(String environment) {
    }

    /** {@code mode} is "password" (default when absent) or "oauth". */
    public record SaveCredentialsBody(String mode, String username, String password,
                                      String clientId, String clientSecret) {
    }

    public record EnvironmentStatus(
        String key,
        String label,
        String apiBaseUrl,
        boolean configured,
        String username,
        String mode,
        boolean oauthAvailable
    ) {
    }

    public record SettingsStatus(String activeEnvironment, List<EnvironmentStatus> environments) {
    }
}
