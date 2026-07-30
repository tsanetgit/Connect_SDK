package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import java.nio.file.Path;

final class HttpsAttachmentConfigArgs {
    private HttpsAttachmentConfigArgs() {
    }

    static NormalizedHttpsAttachmentConfigDto resolve(String[] args) {
        if (CliArgs.configFile(args).isPresent()) {
            return CliJsonFiles.readHttpsConfig(Path.of(CliArgs.configFile(args).get()));
        }
        return new NormalizedHttpsAttachmentConfigDto(
            CliArgs.httpsDomain(args).orElseThrow(() -> missing("--https-domain")),
            CliArgs.httpsPassword(args).orElseThrow(() -> missing("--https-password")),
            CliArgs.httpsExpiration(args).orElseThrow(() -> missing("--https-expiration")),
            CliArgs.httpsPath(args).orElseThrow(() -> missing("--https-path")),
            CliArgs.httpsPort(args).orElseThrow(() -> missing("--https-port"))
        );
    }

    private static IllegalArgumentException missing(String flag) {
        return new IllegalArgumentException("Provide " + flag + " VALUE or --config-file PATH");
    }
}
