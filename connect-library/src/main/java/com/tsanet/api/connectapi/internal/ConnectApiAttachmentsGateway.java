package com.tsanet.api.connectapi.internal;

import static com.tsanet.api.connectapi.internal.OpenApiMapping.enumValue;

import com.tsanet.api.connectapi.AttachmentForwardValidation;
import com.tsanet.api.connectapi.HttpsAttachmentConfigValidation;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.CompanyAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import com.tsanet.api.generated.api.CaseAttachmentsApi;
import com.tsanet.api.generated.model.AttachmentConfigDTO;
import com.tsanet.api.generated.model.AttachmentForwardResultDTO;
import com.tsanet.api.generated.model.CompanyAttachmentConfigDTO;
import com.tsanet.api.generated.model.NormalizedHttpsAttachmentConfigDTO;
import com.tsanet.api.storage.AttachmentConfigStorageService;
import com.tsanet.api.storage.AttachmentForwardResultStorageService;
import java.io.File;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConnectApiAttachmentsGateway {
    private final CaseAttachmentsApi caseAttachmentsApi;
    private final ConnectApiSessionStore sessionStore;
    private final AttachmentConfigStorageService configStorageService;
    private final AttachmentForwardResultStorageService forwardResultStorageService;

    public ConnectApiAttachmentsGateway(
        CaseAttachmentsApi caseAttachmentsApi,
        ConnectApiSessionStore sessionStore,
        AttachmentConfigStorageService configStorageService,
        AttachmentForwardResultStorageService forwardResultStorageService
    ) {
        this.caseAttachmentsApi = caseAttachmentsApi;
        this.sessionStore = sessionStore;
        this.configStorageService = configStorageService;
        this.forwardResultStorageService = forwardResultStorageService;
    }

    public AttachmentConfigDto getAttachmentConfig(String caseToken) {
        requireLogin();

        AttachmentConfigDTO config = caseAttachmentsApi.getAttachmentConfig(caseToken);
        if (config == null) {
            throw new IllegalStateException("Attachment config returned empty response");
        }
        AttachmentConfigDto dto = toDto(config);
        configStorageService.storeFetched(caseToken, dto);
        return dto;
    }

    public List<AttachmentForwardResultDto> forwardAttachments(String caseToken, String description, List<Path> files) {
        requireLogin();

        AttachmentForwardValidation.ValidationResult validation = AttachmentForwardValidation.validate(description, files);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }

        List<File> filePayload = files.stream().map(Path::toFile).toList();
        List<AttachmentForwardResultDTO> results = caseAttachmentsApi.forwardAttachment(
            caseToken,
            description.strip(),
            filePayload
        );
        if (results == null) {
            return Collections.emptyList();
        }
        List<AttachmentForwardResultDto> dtos = results.stream().map(this::toDto).toList();
        forwardResultStorageService.storeForwarded(caseToken, description.strip(), dtos);
        return dtos;
    }

    public NormalizedHttpsAttachmentConfigDto analyzeHttpsAttachmentConfig(
        String caseToken,
        Map<String, Object> requestBody
    ) {
        requireLogin();

        if (requestBody == null || requestBody.isEmpty()) {
            throw new IllegalArgumentException("HTTPS analyze input must not be empty.");
        }

        NormalizedHttpsAttachmentConfigDTO body = caseAttachmentsApi.analyzeConfig(caseToken, requestBody);
        if (body == null) {
            throw new IllegalStateException("HTTPS attachment analyze returned empty response");
        }
        return toHttpsDto(body);
    }

    public AttachmentConfigDto updateHttpsAttachmentConfig(
        String caseToken,
        NormalizedHttpsAttachmentConfigDto config
    ) {
        requireLogin();

        HttpsAttachmentConfigValidation.ValidationResult validation = HttpsAttachmentConfigValidation.validate(config);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }

        AttachmentConfigDTO body = caseAttachmentsApi.createAttachmentConfig(caseToken, toHttpsGenerated(config));
        if (body == null) {
            throw new IllegalStateException("HTTPS attachment config update returned empty response");
        }
        AttachmentConfigDto dto = toDto(body);
        configStorageService.storeFetched(caseToken, dto);
        return dto;
    }

    private NormalizedHttpsAttachmentConfigDto toHttpsDto(NormalizedHttpsAttachmentConfigDTO config) {
        return new NormalizedHttpsAttachmentConfigDto(
            config.getDomain(),
            config.getPassword(),
            config.getExpiration() != null ? config.getExpiration().toString() : null,
            config.getHttpsPath(),
            config.getHttpsPort()
        );
    }

    private NormalizedHttpsAttachmentConfigDTO toHttpsGenerated(NormalizedHttpsAttachmentConfigDto config) {
        NormalizedHttpsAttachmentConfigDTO generated = new NormalizedHttpsAttachmentConfigDTO();
        generated.setDomain(config.domain().strip());
        generated.setPassword(config.password());
        generated.setExpiration(OffsetDateTime.parse(config.expiration().strip()));
        generated.setHttpsPath(config.httpsPath().strip());
        generated.setHttpsPort(config.httpsPort());
        return generated;
    }

    private AttachmentConfigDto toDto(AttachmentConfigDTO config) {
        return new AttachmentConfigDto(
            toCompanyDto(config.getSubmitter()),
            toCompanyDto(config.getReceiver())
        );
    }

    private CompanyAttachmentConfigDto toCompanyDto(CompanyAttachmentConfigDTO company) {
        if (company == null) {
            return null;
        }
        return new CompanyAttachmentConfigDto(company.getCompanyId(), company.getParameters());
    }

    private AttachmentForwardResultDto toDto(AttachmentForwardResultDTO result) {
        return new AttachmentForwardResultDto(
            result.getFileName(),
            enumValue(result.getReceiverStatus()),
            result.getReceiverMessage(),
            enumValue(result.getSubmitterStatus()),
            result.getSubmitterMessage(),
            result.getCompleteSuccess(),
            result.getPartialSuccess()
        );
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}
