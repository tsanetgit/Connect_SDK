package com.tsanet.facade.cli;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class CliJsonFiles {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
