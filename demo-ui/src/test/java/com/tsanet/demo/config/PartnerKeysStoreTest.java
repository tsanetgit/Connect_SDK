package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.demo.E2eeTestVectors;
import com.tsanet.e2ee.E2eeProfile;
import com.tsanet.e2ee.PartnerKeys;
import com.tsanet.e2ee.Records.CasePlaintext;
import com.tsanet.e2ee.Records.EncryptedCase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One environment's partner key table: what it stores, what it refuses (a pasted set with a private
 * key above all), how the seed and the file relate, and that the view answers the demo's own
 * company from its own set and a URL entry through a real fetch (loopback, for the test).
 */
class PartnerKeysStoreTest {

    private static final String SEED_VAR = "TSANET_DEMO_E2EE_PARTNERS_TEST";

    @TempDir
    Path dir;

    private final E2eeTestVectors v = E2eeTestVectors.load();

    private Path file() {
        return dir.resolve("e2ee-partners-test.json");
    }

    @Test
    void aPastedPublicSetIsStoredAndReadBack() throws Exception {
        PartnerKeysStore store = new PartnerKeysStore(file(), null, SEED_VAR);
        store.putJwks("2087", v.publicJwks("memberB"));

        assertThat(store.hasEntry("2087")).isTrue();
        assertThat(store.entries().get("2087").source()).isEqualTo(PartnerKeysStore.SOURCE_JWKS);
        assertThat(store.entries().get("2087").kids()).containsExactly(v.rsa("memberB_enc").getKeyID());
        // The property is the file: a new store over the same path holds the same entry.
        PartnerKeysStore reloaded = new PartnerKeysStore(file(), null, SEED_VAR);
        assertThat(reloaded.entries().get("2087").kids()).containsExactly(v.rsa("memberB_enc").getKeyID());
        assertThat(Files.readString(file())).contains("\"2087\"").doesNotContain("\"d\"");
    }

    @Test
    void aPastedSetWithAPrivateKeyIsRefusedAndNothingIsWritten() {
        PartnerKeysStore store = new PartnerKeysStore(file(), null, SEED_VAR);
        String withPrivate = v.memberBPrivateJwks();

        String privateD = v.rsa("memberB_enc").getPrivateExponent().toString().substring(0, 16);
        assertThatThrownBy(() -> store.putJwks("2087", withPrivate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(privateD));
        assertThat(store.hasEntry("2087")).isFalse();
        assertThat(file()).doesNotExist();
        // Nor through the seed or a hand-edited file; neither message quotes the set.
        String seed = "{\"2087\": {\"jwks\": " + withPrivate + "}}";
        assertThatThrownBy(() -> new PartnerKeysStore(file(), seed, SEED_VAR))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(SEED_VAR)
            .hasMessageContaining("private")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(privateD));
    }

    @Test
    void aUrlMustBeHttpsOrLoopbackAndACompanyIdANumber() {
        PartnerKeysStore store = new PartnerKeysStore(file(), null, SEED_VAR);

        assertThatThrownBy(() -> store.putUrl("2087", URI.create("http://keys.partner.example/jwks.json")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> store.putUrl("2087", URI.create("https:keys.partner.example")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("has no usable host");
        assertThatThrownBy(() -> store.putUrl("2087", URI.create("https://keys_partner/jwks.json")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("has no usable host");
        assertThatThrownBy(() -> store.putUrl("acme", URI.create("https://keys.partner.example/jwks.json")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.putJwks("", v.publicJwks("memberB"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.entries()).isEmpty();
        assertThat(file()).doesNotExist();

        store.putUrl("2087", URI.create("https://keys.partner.example/.well-known/jwks.json"));
        assertThat(store.entries().get("2087").source()).isEqualTo(PartnerKeysStore.SOURCE_URL);
        assertThat(store.entries().get("2087").kids()).isEmpty();
    }

    @Test
    void theSeedAppliesOnlyWhenTheFileIsAbsent() throws Exception {
        String seed = "{\"2087\": {\"jwksUrl\": \"https://keys.partner.example/jwks.json\"},"
            + " \"3104\": {\"jwks\": " + v.publicJwks("memberA") + "}}";

        PartnerKeysStore seeded = new PartnerKeysStore(file(), seed, SEED_VAR);
        assertThat(seeded.entries().keySet()).containsExactly("2087", "3104");
        assertThat(file()).as("the seed itself isn't written; the first edit is").doesNotExist();

        // Removing every entry leaves an empty file, which still wins over the seed on the next start.
        seeded.remove("2087");
        seeded.remove("3104");
        assertThat(file()).exists();
        assertThat(new PartnerKeysStore(file(), seed, SEED_VAR).entries()).isEmpty();

        // And a file with entries is never overwritten by the seed.
        PartnerKeysStore edited = new PartnerKeysStore(file(), null, SEED_VAR);
        edited.putUrl("5000", URI.create("https://keys.other.example/jwks.json"));
        assertThat(new PartnerKeysStore(file(), seed, SEED_VAR).entries().keySet()).containsExactly("5000");
    }

    @Test
    void aMalformedSeedNamesItsVariable() throws Exception {
        assertThatThrownBy(() -> new PartnerKeysStore(file(), "not json", SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(SEED_VAR);
        assertThatThrownBy(() -> new PartnerKeysStore(file(), "{\"2087\": {}}", SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one of jwksUrl and jwks");
        assertThatThrownBy(() -> new PartnerKeysStore(file(), "{\"acme\": {\"jwksUrl\": \"https://x\"}}", SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("numeric company id");
        // Well-formed but wrong: a plain-http URL, through the seed and through a hand-edited file.
        String plainHttp = "{\"2087\": {\"jwksUrl\": \"http://keys.partner.example/jwks.json\"}}";
        assertThatThrownBy(() -> new PartnerKeysStore(file(), plainHttp, SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(SEED_VAR).hasMessageContaining("HTTPS");
        Files.writeString(file(), plainHttp);
        assertThatThrownBy(() -> new PartnerKeysStore(file(), null, SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Fix or delete the file").hasMessageContaining("HTTPS");
        // Well-formed but wrong the same way: https, with no host to fetch from.
        Files.delete(file());
        String noHost = "{\"2087\": {\"jwksUrl\": \"https:keys.partner.example\"}}";
        assertThatThrownBy(() -> new PartnerKeysStore(file(), noHost, SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(SEED_VAR).hasMessageContaining("has no usable host");
        Files.writeString(file(), noHost);
        assertThatThrownBy(() -> new PartnerKeysStore(file(), null, SEED_VAR))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Fix or delete the file").hasMessageContaining("has no usable host");
    }

    @Test
    void theViewAnswersTheOwnCompanyFromItsOwnSetAndRefusesAnUnknownOne() throws Exception {
        PartnerKeysStore store = new PartnerKeysStore(file(), null, SEED_VAR);
        store.putJwks("2087", v.publicJwks("memberB"));
        JWKSet own = JWKSet.parse(v.publicJwks("memberA"));
        PartnerKeys view = store.view("1042", own);

        assertThat(view.publicKeys("1042")).isSameAs(own);
        assertThat(view.publicKeys("2087").getKeyByKeyId(v.rsa("memberB_enc").getKeyID())).isNotNull();
        assertThatThrownBy(() -> view.publicKeys("9999")).isInstanceOf(IOException.class).hasMessageContaining("9999");
        // An edit after the view was taken is seen on the next call: the profile holds the view, not a snapshot.
        store.remove("2087");
        assertThatThrownBy(() -> view.publicKeys("2087")).isInstanceOf(IOException.class).hasMessageContaining("2087");
    }

    @Test
    void aUrlEntryIsFetchedAndTheDemoEncryptsToIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] published = v.publicJwks("memberB").getBytes(StandardCharsets.UTF_8);
        server.createContext("/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, published.length);
            try (var out = exchange.getResponseBody()) {
                out.write(published);
            }
        });
        server.start();
        try {
            PartnerKeysStore store = new PartnerKeysStore(file(), null, SEED_VAR);
            store.putUrl("2087", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json"));
            E2eeKeys keys = E2eeKeys.fromJson(v.memberAPrivateJwks());
            E2eeProfile a = new E2eeProfile("1042", keys.keyOperations(), store.view("1042", keys.publicKeySet()),
                keys.replayGuard(), keys.clock());

            EncryptedCase out = a.encryptCase(new CasePlaintext("A-1", "hello", "world", Map.of(), List.of()), "2087");

            assertThat(out.e2ee().encrypted()).isTrue();
            assertThat(out.summary()).startsWith("{\"protected\"");
            JWKSet fetched = store.view("1042", keys.publicKeySet()).publicKeys("2087");
            for (JWK key : fetched.getKeys()) {
                assertThat(key.isPrivate()).isFalse();
            }
        } finally {
            server.stop(0);
        }
    }
}
