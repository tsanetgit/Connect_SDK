package com.tsanet.facade.cli;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class CliJsonFiles {
    // Jackson 3 flipped FAIL_ON_UNKNOWN_PROPERTIES to lenient by default; Jackson 2
    // rejected unknown keys. Re-enabled so a typo'd key in an operator-authored
    // config file fails loudly (as on main) instead of silently leaving the field
    // unset. The Map reads are unaffected: the feature only applies to typed binding.
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private CliJsonFiles() {
    }

    static Map<String, Object> readObjectMap(Path path) {
        try {
            return OBJECT_MAPPER.readValue(Files.readString(path), new TypeReference<>() {});
        } catch (IOException | JacksonException ex) {
            throw new IllegalArgumentException("Failed to read JSON file " + path + ": " + ex.getMessage());
        }
    }

    static NormalizedHttpsAttachmentConfigDto readHttpsConfig(Path path) {
        try {
            return OBJECT_MAPPER.readValue(Files.readString(path), NormalizedHttpsAttachmentConfigDto.class);
        } catch (IOException | JacksonException ex) {
            throw new IllegalArgumentException("Failed to read HTTPS config file " + path + ": " + ex.getMessage());
        }
    }
}
