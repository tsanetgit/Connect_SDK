package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.model.CompanyDTO;
import com.tsanet.api.generated.model.UserContextDTO;
import com.tsanet.api.generated.model.UserDTO;
import com.tsanet.api.storage.UserContextStorageService;

public class ConnectApiUserGateway {
    private final IdentityApi identityApi;
    private final ConnectApiSessionStore sessionStore;
    private final UserContextStorageService storageService;

    public ConnectApiUserGateway(
        IdentityApi identityApi,
        ConnectApiSessionStore sessionStore,
        UserContextStorageService storageService
    ) {
        this.identityApi = identityApi;
        this.sessionStore = sessionStore;
        this.storageService = storageService;
    }

    public UserContextDto getCurrentUser() {
        requireLogin();

        UserContextDTO body = identityApi.getCurrentUser();
        if (body == null) {
            throw new IllegalStateException("Current user response is empty");
        }
        UserContextDto userContext = toDto(body);
        storageService.storeFetched(userContext);
        return userContext;
    }

    private UserContextDto toDto(UserContextDTO dto) {
        CompanyDTO company = dto.getCompany();
        UserDTO user = dto.getUser();
        return new UserContextDto(
            company != null ? company.getId() : null,
            company != null ? company.getName() : null,
            user != null ? user.getId() : null,
            user != null ? user.getUsername() : null,
            user != null ? user.getEmail() : null,
            user != null ? user.getFirstName() : null,
            user != null ? user.getLastName() : null
        );
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}
