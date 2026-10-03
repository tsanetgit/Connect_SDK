package com.tsanet.demo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.StorageConfig;
import com.tsanet.api.attachments.v2.StorageTarget;
import com.tsanet.api.attachments.v2.StorageTestResult;
import com.tsanet.api.facade.AttachmentStorageFacade;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Settings page's V2 receiver storage endpoints: each environment's card works through that
 * environment's own session, and with {@code tsanet.demo.receiver-storage-editable} off the
 * register endpoint refuses before building any session, while read and test still work.
 */
class ReceiverStorageSettingsTest {

    private static final String S3_BODY = "{\"method\":\"s3\",\"bucket\":\"acme-attachments\",\"region\":\"us-east-1\","
        + "\"roleArn\":\"arn:aws:iam::123456789012:role/tsanet-writer\",\"prefix\":\"  \"}";

    @TempDir
    Path dataDir;

    private final SessionGuard guard = mock(SessionGuard.class);
    private final AttachmentStorageFacade storage = mock(AttachmentStorageFacade.class);

    /** Two environments, beta active; dev is the one a card names in these tests. */
    private MockMvc mvc(Boolean editable) {
        DemoProperties properties = new DemoProperties(
            Map.of(
                "beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null),
                "dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString(),
            true,  // never contacted: the session is a mock
            editable
        );
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        when(session.attachmentStorage()).thenReturn(storage);
        when(guard.session("dev")).thenReturn(session);
        return MockMvcBuilders.standaloneSetup(new SettingsController(new EnvironmentService(properties), guard, properties))
            .setControllerAdvice(new ApiErrorHandler())
            .build();
    }

    private static StorageConfig s3Config() {
        return new StorageConfig(new StorageTarget.S3("acme-attachments", "us-east-1",
            "arn:aws:iam::123456789012:role/tsanet-writer", null), "ext-abc", StorageConfig.Verification.NEVER_TESTED, null);
    }

    @Test
    void withEditingOffRegisteringIsRefusedBeforeAnySession() throws Exception {
        mvc(null).perform(put("/api/settings/dev/receiver-storage").contentType(MediaType.APPLICATION_JSON).content(S3_BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("receiver-storage-editable")));

        verifyNoInteractions(guard, storage);
    }

    @Test
    void withEditingOffReadingAndTestingStillWorkAndSayItIsntEditable() throws Exception {
        when(storage.get()).thenReturn(Optional.of(s3Config()));
        when(storage.test()).thenReturn(new StorageTestResult(false, OffsetDateTime.parse("2026-10-03T12:05:00Z"),
            "AccessDenied assuming the role"));
        MockMvc mvc = mvc(false);

        mvc.perform(get("/api/settings/dev/receiver-storage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editable").value(false))
            .andExpect(jsonPath("$.config.method").value("s3"))
            .andExpect(jsonPath("$.config.bucket").value("acme-attachments"))
            .andExpect(jsonPath("$.config.externalId").value("ext-abc"))
            .andExpect(jsonPath("$.config.verification").value("NEVER_TESTED"));
        mvc.perform(post("/api/settings/dev/receiver-storage/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(false))
            .andExpect(jsonPath("$.detail").value("AccessDenied assuming the role"));
    }

    @Test
    void nothingRegisteredReadsAsANullConfig() throws Exception {
        when(storage.get()).thenReturn(Optional.empty());

        mvc(true).perform(get("/api/settings/dev/receiver-storage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editable").value(true))
            .andExpect(jsonPath("$.config").doesNotExist());
    }

    @Test
    void withEditingOnAnS3TargetIsRegisteredThroughThatEnvironmentsSession() throws Exception {
        StorageTarget.S3 expected = new StorageTarget.S3("acme-attachments", "us-east-1",
            "arn:aws:iam::123456789012:role/tsanet-writer", null);
        when(storage.register(expected)).thenReturn(s3Config());

        mvc(true).perform(put("/api/settings/dev/receiver-storage").contentType(MediaType.APPLICATION_JSON).content(S3_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.config.externalId").value("ext-abc"));

        verify(guard).session("dev");
        verify(guard, never()).session();
        verify(storage).register(expected);
    }

    @Test
    void withEditingOnAnAzureBlobTargetIsRegistered() throws Exception {
        StorageTarget.AzureBlob expected = new StorageTarget.AzureBlob("inbound", "tenant-1", "acmestore", "tsanet");
        when(storage.register(expected)).thenReturn(new StorageConfig(expected, null,
            StorageConfig.Verification.NEVER_TESTED, null));

        mvc(true).perform(put("/api/settings/dev/receiver-storage").contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"azureBlob\",\"container\":\"inbound\",\"tenantId\":\"tenant-1\","
                    + "\"storageAccountName\":\"acmestore\",\"prefix\":\" tsanet \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.config.method").value("azureBlob"))
            .andExpect(jsonPath("$.config.container").value("inbound"))
            .andExpect(jsonPath("$.config.externalId").doesNotExist());
        verify(storage).register(expected);
    }

    @Test
    void anUnknownMethodOrAMissingFieldIsRefusedBeforeAnySession() throws Exception {
        MockMvc mvc = mvc(true);

        mvc.perform(put("/api/settings/dev/receiver-storage").contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"gcs\",\"bucket\":\"b\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("method must be s3 or azureBlob"));
        mvc.perform(put("/api/settings/dev/receiver-storage").contentType(MediaType.APPLICATION_JSON)
                .content(S3_BODY.replace("\"bucket\":\"acme-attachments\",", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bucket must not be blank"));

        verifyNoInteractions(guard, storage);
    }

    @Test
    void thePlatformsRefusalKeepsItsCode() throws Exception {
        when(storage.register(any())).thenThrow(new AttachmentV2Exception("register storage config failed: Bad Request",
            400, AttachmentV2Exception.INVALID_REQUEST));

        mvc(true).perform(put("/api/settings/dev/receiver-storage").contentType(MediaType.APPLICATION_JSON).content(S3_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("attachment/invalid-request: register storage config failed: Bad Request"));
    }

    @Test
    void anUnknownEnvironmentIsRefused() throws Exception {
        mvc(true).perform(get("/api/settings/prod/receiver-storage"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(guard);
    }
}
