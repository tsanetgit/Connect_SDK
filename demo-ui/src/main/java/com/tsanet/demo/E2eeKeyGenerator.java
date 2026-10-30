package com.tsanet.demo;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/**
 * Prints a fresh private JWK set for {@code TSANET_DEMO_E2EE_PRIVATE_JWKS}: one RSA-OAEP-256
 * encryption key (2048 bits, so the set fits an SSM standard-tier parameter) and one P-256
 * signing key, each with a {@code kid}, {@code use} and {@code alg}. For the operator to run once
 * and put into SSM or a local env file (docs/RUNBOOK.md); it is on no request path, and the set
 * goes to standard output only. Not a key rotation tool: the demo holds one current pair
 * (tsanetgit/Connect_SDK#149).
 *
 * <pre>java -cp demo-ui/target/demo-ui-0.0.1-SNAPSHOT.jar \
 *   -Dloader.main=com.tsanet.demo.E2eeKeyGenerator org.springframework.boot.loader.launch.PropertiesLauncher</pre>
 */
public final class E2eeKeyGenerator {

    private static final int RSA_BITS = 2048;

    private E2eeKeyGenerator() {
    }

    public static void main(String[] args) throws JOSEException {
        System.out.println(generate());
    }

    /** One private set as compact JSON; the kids carry the date and a random suffix. */
    static String generate() throws JOSEException {
        String stamp = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
            + "-" + HexFormat.of().formatHex(randomBytes(4));
        RSAKey encryption = new RSAKeyGenerator(RSA_BITS)
            .keyID("tsanet-demo-enc-" + stamp)
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .generate();
        ECKey signing = new ECKeyGenerator(Curve.P_256)
            .keyID("tsanet-demo-sig-" + stamp)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.ES256)
            .generate();
        // false: keep the private members; this is the set the operator stores, not the one published.
        return new JWKSet(List.of(encryption, signing)).toString(false);
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
