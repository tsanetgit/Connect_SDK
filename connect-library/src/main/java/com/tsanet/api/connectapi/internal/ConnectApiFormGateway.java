package com.tsanet.api.connectapi.internal;

import com.tsanet.api.generated.api.FormRequestApi;
import com.tsanet.api.generated.model.CollaborationRequestDTO;

public class ConnectApiFormGateway {
    private final FormRequestApi formRequestApi;
    private final ConnectApiSessionStore sessionStore;

    public ConnectApiFormGateway(FormRequestApi formRequestApi, ConnectApiSessionStore sessionStore) {
        this.formRequestApi = formRequestApi;
        this.sessionStore = sessionStore;
    }

    public CollaborationRequestDTO getFormByCompanyId(long companyId) {
        requireLogin();
        CollaborationRequestDTO form = formRequestApi.getFormByCompanyId(companyId);
        return requireForm(form, "company id=" + companyId);
    }

    public CollaborationRequestDTO getFormByDepartmentId(long departmentId) {
        requireLogin();
        CollaborationRequestDTO form = formRequestApi.getFormByDepartmentId(departmentId);
        return requireForm(form, "department id=" + departmentId);
    }

    public CollaborationRequestDTO getFormByDocumentId(long documentId) {
        requireLogin();
        CollaborationRequestDTO form = formRequestApi.getFormByDocumentId(documentId);
        return requireForm(form, "document id=" + documentId);
    }

    private static CollaborationRequestDTO requireForm(CollaborationRequestDTO form, String label) {
        if (form == null) {
            throw new IllegalStateException("Form template for " + label + " returned empty response");
        }
        return form;
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}
