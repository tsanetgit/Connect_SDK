package com.tsanet.facade.session;

import com.tsanet.api.TsaNetApiSession;

public final class AccountCacheSummary {
    private final int requestCount;
    private final int noteCount;
    private final int responseCount;
    private final boolean hasStoredUser;

    public AccountCacheSummary(int requestCount, int noteCount, int responseCount, boolean hasStoredUser) {
        this.requestCount = requestCount;
        this.noteCount = noteCount;
        this.responseCount = responseCount;
        this.hasStoredUser = hasStoredUser;
    }

    public static AccountCacheSummary from(TsaNetApiSession session) {
        return new AccountCacheSummary(
            session.collaborationRequests().listStoredRequests().size(),
            session.caseNotes().listStoredNotes().size(),
            session.caseResponses().listStoredResponses().size(),
            !session.users().listStoredUsers().isEmpty()
        );
    }

    public int requestCount() {
        return requestCount;
    }

    public int noteCount() {
        return noteCount;
    }

    public int responseCount() {
        return responseCount;
    }

    public boolean hasStoredUser() {
        return hasStoredUser;
    }

    public boolean isEmpty() {
        return requestCount == 0 && noteCount == 0 && responseCount == 0 && !hasStoredUser;
    }

    public String describe() {
        if (isEmpty()) {
            return "No cached context for this account yet.";
        }
        return "Cached context: "
            + requestCount + " request(s), "
            + noteCount + " note(s), "
            + responseCount + " response(s)";
    }
}
