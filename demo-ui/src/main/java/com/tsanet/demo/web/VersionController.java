package com.tsanet.demo.web;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The build the page is running, from the git.properties stamped into the jar. Every module builds
 * as 0.0.1-SNAPSHOT, so the Maven version can't say. Behind the gate, unlike /healthz.
 */
@RestController
public class VersionController {

    static final String UNKNOWN = "unknown";

    private final ObjectProvider<GitProperties> git;

    public VersionController(ObjectProvider<GitProperties> git) {
        this.git = git;
    }

    @GetMapping("/api/version")
    public ResponseEntity<Map<String, String>> version() {
        // no-store: after a redeploy the page must not show the build it cached.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Map.of("version", label(git.getIfAvailable())));
    }

    /** "v5.0.0 · 47cdb4a" on a tag, "v5.0.0-3-g01cde11" past one, "47cdb4a" with no tags in the checkout. */
    static String label(GitProperties properties) {
        if (properties == null) {
            return UNKNOWN;
        }
        String describe = properties.get("commit.id.describe");
        String sha = properties.getShortCommitId();
        boolean noSha = sha == null || sha.isBlank();
        if (describe == null || describe.isBlank()) {
            return noSha ? UNKNOWN : sha;
        }
        return noSha || describe.contains(sha) ? describe : describe + " · " + sha;
    }
}
