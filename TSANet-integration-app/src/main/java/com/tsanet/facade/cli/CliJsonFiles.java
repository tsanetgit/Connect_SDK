package com.tsanet.facade.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read JSON file " + path + ": " + ex.getMessage());
        }
    }

    static NormalizedHttpsAttachmentConfigDto readHttpsConfig(Path path) {
        try {
            return OBJECT_MAPPER.readValue(Files.readString(path), NormalizedHttpsAttachmentConfigDto.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read HTTPS config file " + path + ": " + ex.getMessage());
        }
    }
}
