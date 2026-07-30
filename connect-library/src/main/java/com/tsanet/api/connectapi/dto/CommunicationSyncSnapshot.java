package com.tsanet.api.connectapi.dto;

public record CommunicationSyncSnapshot(
    int requestCount,
    int noteCount,
    int responseCount,
    int attachmentConfigCount,
    int webhookSubscriptionCount,
    boolean userContextSynced
) {
    public String summarize() {
        return requestCount + " request(s), "
            + noteCount + " note(s), "
            + responseCount + " response(s), "
            + attachmentConfigCount + " attachment config(s), "
            + webhookSubscriptionCount + " webhook(s), "
            + "user=" + (userContextSynced ? "synced" : "skipped");
    }
}
