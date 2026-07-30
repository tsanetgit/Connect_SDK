package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class CollaborationRequestResolver {
    private final TsaNetApiSession session;

    public CollaborationRequestResolver(TsaNetApiSession session) {
        this.session = session;
    }

    public void requireAuthentication() {
        if (!session.auth().isAuthorized()) {
            throw new IllegalStateException("Authentication required. Use 'login' first.");
        }
    }

    public CollaborationRequestStatusDto resolve(String[] args) {
        if (CliArgs.token(args).isPresent()) {
            return findByToken(CliArgs.token(args).get());
        }
        Long requestId = CliArgs.requestId(args)
            .orElseThrow(() -> new IllegalArgumentException("Provide --id REQUEST_ID or --token CASE_TOKEN"));
        return findById(requestId);
    }

    private CollaborationRequestStatusDto findById(long requestId) {
        return session.collaborationRequests().listStoredRequests().stream()
            .filter(request -> Objects.equals(requestId, request.id()))
            .findFirst()
            .or(() -> session.collaborationRequests().listRequests().stream()
                .filter(request -> Objects.equals(requestId, request.id()))
                .findFirst())
            .orElseThrow(() -> new IllegalArgumentException("Collaboration request id=" + requestId + " not found"));
    }

    private CollaborationRequestStatusDto findByToken(String caseToken) {
        return session.collaborationRequests().listStoredRequests().stream()
            .filter(request -> caseToken.equals(request.token()))
            .findFirst()
            .or(() -> session.collaborationRequests().listRequests().stream()
                .filter(request -> caseToken.equals(request.token()))
                .findFirst())
            .orElseThrow(() -> new IllegalArgumentException("Collaboration request token=" + caseToken + " not found"));
    }
}
