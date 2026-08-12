package com.tsanet.demo.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Properties;

/**
 * Holds member credentials for one environment, entered via the settings UI.
 * Two modes: {@code password} (username + password for /v1/login) and
 * {@code oauth} (client id + secret for the Entra client-credentials grant).
 * Stored in a plain properties file outside the repo (never logged, never
 * returned by the settings API) so the demo can run without env vars or a
 * redeploy. Instantiated per environment by {@link EnvironmentService}.
 */
public class CredentialsStore {

    public static final String MODE_PASSWORD = "password";
    public static final String MODE_OAUTH = "oauth";

    private final Path path;

    public CredentialsStore(Path path) {
        this.path = path;
    }

    public synchronized Optional<Credentials> load() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Files written before OAuth support have no mode key: password mode.
        String mode = props.getProperty("mode", MODE_PASSWORD);
        if (MODE_OAUTH.equals(mode)) {
            String clientId = props.getProperty("clientId");
            String clientSecret = props.getProperty("clientSecret");
            if (isBlank(clientId) || isBlank(clientSecret)) {
                return Optional.empty();
            }
            return Optional.of(Credentials.oauth(clientId, clientSecret));
        }
        String username = props.getProperty("username");
        String password = props.getProperty("password");
        if (isBlank(username) || isBlank(password)) {
            return Optional.empty();
        }
        return Optional.of(Credentials.password(username, password));
    }

    public synchronized void save(String username, String password) {
        Properties props = new Properties();
        props.setProperty("mode", MODE_PASSWORD);
        props.setProperty("username", username);
        props.setProperty("password", password);
        write(props);
    }

    public synchronized void saveOAuth(String clientId, String clientSecret) {
        Properties props = new Properties();
        props.setProperty("mode", MODE_OAUTH);
        props.setProperty("clientId", clientId);
        props.setProperty("clientSecret", clientSecret);
        write(props);
    }

    private void write(Properties props) {
        try {
            Files.createDirectories(path.getParent());
            try (var out = Files.newOutputStream(path)) {
                props.store(out, "TSANet demo credentials - do not commit");
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Mode-tagged credentials. In password mode {@code username}/{@code password}
     * are set; in OAuth mode {@code clientId}/{@code clientSecret} are.
     * {@link #principal()} is the display identity for either mode.
     */
    public record Credentials(String mode, String username, String password, String clientId, String clientSecret) {

        static Credentials password(String username, String password) {
            return new Credentials(MODE_PASSWORD, username, password, null, null);
        }

        static Credentials oauth(String clientId, String clientSecret) {
            return new Credentials(MODE_OAUTH, null, null, clientId, clientSecret);
        }

        public boolean isOAuth() {
            return MODE_OAUTH.equals(mode);
        }

        public String principal() {
            return isOAuth() ? clientId : username;
        }
    }
}
