package com.tsanet.demo.web;

import com.tsanet.demo.config.E2eeKeys;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's published public keys (tsanetgit/Connect_SDK#149), outside the Basic gate like
 * {@code /healthz}, so a partner's key fetcher can pin this URL. The body is
 * {@link E2eeKeys#publicJson()}: every private member dropped. With E2EE off the path is a 404,
 * as before the endpoint existed.
 */
@RestController
public class JwksController {

    public static final String PATH = "/.well-known/jwks.json";

    private final E2eeKeys keys;

    public JwksController(E2eeKeys keys) {
        this.keys = keys;
    }

    @GetMapping(PATH)
    public ResponseEntity<String> jwks() {
        if (!keys.enabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(keys.publicJson());
    }
}
