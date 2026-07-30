package com.tsanet.api.internal;

import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.internal.ConnectApiAttachmentsGateway;
import com.tsanet.api.connectapi.internal.ConnectApiAuthGateway;
import com.tsanet.api.connectapi.internal.ConnectApiCollaborationGateway;
import com.tsanet.api.connectapi.internal.ConnectApiFormGateway;
import com.tsanet.api.connectapi.internal.ConnectApiNotesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiPartnersGateway;
import com.tsanet.api.connectapi.internal.ConnectApiResponsesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiSessionStore;
import com.tsanet.api.connectapi.internal.ConnectApiUserGateway;
import com.tsanet.api.connectapi.internal.ConnectApiWebhooksGateway;
import com.tsanet.api.generated.api.CaseAttachmentsApi;
import com.tsanet.api.generated.api.CaseNotesApi;
import com.tsanet.api.generated.api.CaseResponsesApi;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.api.EntitySearchApi;
import com.tsanet.api.generated.api.FormRequestApi;
import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.api.WebhooksApi;
import com.tsanet.api.generated.api.WebhooksV1Api;
import com.tsanet.api.generated.invoker.ApiClient;
import com.tsanet.api.storage.AttachmentConfigRepository;
import com.tsanet.api.storage.AttachmentConfigStorageService;
import com.tsanet.api.storage.AttachmentForwardResultRepository;
import com.tsanet.api.storage.AttachmentForwardResultStorageService;
import com.tsanet.api.storage.CaseNoteRepository;
import com.tsanet.api.storage.CaseNoteStorageService;
import com.tsanet.api.storage.CaseResponseRepository;
import com.tsanet.api.storage.CaseResponseStorageService;
import com.tsanet.api.storage.CollaborationRequestFormRepository;
import com.tsanet.api.storage.CollaborationRequestFormStorageService;
import com.tsanet.api.storage.CollaborationRequestRepository;
import com.tsanet.api.storage.CollaborationRequestStorageService;
import com.tsanet.api.storage.DatabaseInitializer;
import com.tsanet.api.storage.PartnerSelectionRepository;
import com.tsanet.api.storage.PartnerSelectionStorageService;
import com.tsanet.api.storage.UserContextRepository;
import com.tsanet.api.storage.UserContextStorageService;
import com.tsanet.api.storage.WebhookInboundEventRepository;
import com.tsanet.api.storage.WebhookInboundEventStorageService;
import com.tsanet.api.storage.WebhookSubscriptionRepository;
import com.tsanet.api.storage.WebhookSubscriptionStorageService;
import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.connectapi.internal.OAuth401RetryInterceptor;
import com.tsanet.api.connectapi.internal.OAuthTokenGateway;
import com.tsanet.api.connectapi.internal.TokenManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

public final class TsaNetApiRuntime {
    private TsaNetApiRuntime() {
    }

    public static TsaNetApiSession create(TsaNetApiConfiguration configuration) {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();

        RestTemplate restTemplate = new RestTemplate(
            new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(configuration.apiBaseUrl());

        IdentityApi identityApi = new IdentityApi(apiClient);
        ConnectApiAuthGateway authGateway = new ConnectApiAuthGateway(identityApi);
        OAuthTokenGateway oauthTokenGateway = new OAuthTokenGateway();
        TokenManager tokenManager = new TokenManager(
            sessionStore,
            authGateway,
            oauthTokenGateway,
            configuration.accountId(),
            configuration.auth()
        );
        if (configuration.auth().mode() == AuthMode.CLIENT_CREDENTIALS) {
            restTemplate.getInterceptors().add(new OAuth401RetryInterceptor(tokenManager));
        }
        apiClient.setBearerToken(() -> bearerTokenForRequest(sessionStore, tokenManager, configuration));

        CollaborationRequestsApi collaborationRequestsApi = new CollaborationRequestsApi(apiClient);
        CaseNotesApi caseNotesApi = new CaseNotesApi(apiClient);
        CaseAttachmentsApi caseAttachmentsApi = new CaseAttachmentsApi(apiClient);
        CaseResponsesApi caseResponsesApi = new CaseResponsesApi(apiClient);
        WebhooksApi webhooksApi = new WebhooksApi(apiClient);
        WebhooksV1Api webhooksV1Api = new WebhooksV1Api(apiClient);
        EntitySearchApi entitySearchApi = new EntitySearchApi(apiClient);
        FormRequestApi formRequestApi = new FormRequestApi(apiClient);

        JdbcTemplate jdbcTemplate = createJdbcTemplate(configuration.sqlitePath());
        DatabaseInitializer.createSchema(jdbcTemplate);

        CollaborationRequestRepository collaborationRequestRepository = new CollaborationRequestRepository(jdbcTemplate);
        CaseNoteRepository caseNoteRepository = new CaseNoteRepository(jdbcTemplate);
        CaseResponseRepository caseResponseRepository = new CaseResponseRepository(jdbcTemplate);
        UserContextRepository userContextRepository = new UserContextRepository(jdbcTemplate);
        WebhookSubscriptionRepository webhookSubscriptionRepository = new WebhookSubscriptionRepository(jdbcTemplate);
        PartnerSelectionRepository partnerSelectionRepository = new PartnerSelectionRepository(jdbcTemplate);
        CollaborationRequestFormRepository collaborationRequestFormRepository =
            new CollaborationRequestFormRepository(jdbcTemplate);
        AttachmentConfigRepository attachmentConfigRepository = new AttachmentConfigRepository(jdbcTemplate);
        AttachmentForwardResultRepository attachmentForwardResultRepository =
            new AttachmentForwardResultRepository(jdbcTemplate);

        CollaborationRequestStorageService collaborationRequestStorageService =
            new CollaborationRequestStorageService(collaborationRequestRepository);
        CollaborationRequestFormStorageService collaborationRequestFormStorageService =
            new CollaborationRequestFormStorageService(collaborationRequestFormRepository);
        CaseNoteStorageService caseNoteStorageService = new CaseNoteStorageService(caseNoteRepository);
        CaseResponseStorageService caseResponseStorageService = new CaseResponseStorageService(caseResponseRepository);
        UserContextStorageService userContextStorageService = new UserContextStorageService(userContextRepository);
        WebhookSubscriptionStorageService webhookSubscriptionStorageService =
            new WebhookSubscriptionStorageService(webhookSubscriptionRepository);
        WebhookInboundEventRepository webhookInboundEventRepository = new WebhookInboundEventRepository(jdbcTemplate);
        WebhookInboundEventStorageService webhookInboundEventStorageService =
            new WebhookInboundEventStorageService(webhookInboundEventRepository);
        PartnerSelectionStorageService partnerSelectionStorageService =
            new PartnerSelectionStorageService(partnerSelectionRepository);
        AttachmentConfigStorageService attachmentConfigStorageService =
            new AttachmentConfigStorageService(attachmentConfigRepository);
        AttachmentForwardResultStorageService attachmentForwardResultStorageService =
            new AttachmentForwardResultStorageService(attachmentForwardResultRepository);

        ConnectApiCollaborationGateway collaborationGateway = new ConnectApiCollaborationGateway(
            collaborationRequestsApi,
            sessionStore,
            collaborationRequestStorageService
        );
        ConnectApiFormGateway formGateway = new ConnectApiFormGateway(formRequestApi, sessionStore);
        ConnectApiNotesGateway notesGateway = new ConnectApiNotesGateway(
            caseNotesApi,
            sessionStore,
            caseNoteStorageService
        );
        ConnectApiResponsesGateway responsesGateway = new ConnectApiResponsesGateway(
            collaborationRequestsApi,
            caseResponsesApi,
            sessionStore,
            caseResponseStorageService,
            collaborationRequestStorageService
        );
        ConnectApiUserGateway userGateway = new ConnectApiUserGateway(
            identityApi,
            sessionStore,
            userContextStorageService
        );
        ConnectApiWebhooksGateway webhooksGateway = new ConnectApiWebhooksGateway(
            webhooksV1Api,
            webhooksApi,
            sessionStore,
            webhookSubscriptionStorageService
        );
        ConnectApiPartnersGateway partnersGateway = new ConnectApiPartnersGateway(
            entitySearchApi,
            sessionStore,
            partnerSelectionStorageService
        );
        ConnectApiAttachmentsGateway attachmentsGateway = new ConnectApiAttachmentsGateway(
            caseAttachmentsApi,
            sessionStore,
            attachmentConfigStorageService,
            attachmentForwardResultStorageService
        );

        return new DefaultTsaNetApiSession(
            configuration,
            sessionStore,
            tokenManager,
            authGateway,
            collaborationGateway,
            formGateway,
            notesGateway,
            responsesGateway,
            userGateway,
            webhooksGateway,
            partnersGateway,
            attachmentsGateway,
            collaborationRequestStorageService,
            collaborationRequestFormStorageService,
            caseNoteStorageService,
            caseResponseStorageService,
            userContextStorageService,
            webhookSubscriptionStorageService,
            webhookInboundEventStorageService,
            partnerSelectionStorageService,
            attachmentConfigStorageService,
            attachmentForwardResultStorageService
        );
    }

    private static String bearerTokenForRequest(
        ConnectApiSessionStore sessionStore,
        TokenManager tokenManager,
        TsaNetApiConfiguration configuration
    ) {
        if (!sessionStore.getBearerToken().isPresent()) {
            return null;
        }
        if (configuration.auth().mode() == AuthMode.CLIENT_CREDENTIALS) {
            return tokenManager.ensureValidAccessToken();
        }
        return sessionStore.getBearerToken().orElse(null);
    }

    private static JdbcTemplate createJdbcTemplate(String sqlitePath) {
        try {
            Path dbPath = Path.of(sqlitePath).toAbsolutePath().normalize();
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + dbPath);
            return new JdbcTemplate(dataSource);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize SQLite storage at " + sqlitePath, ex);
        }
    }
}
