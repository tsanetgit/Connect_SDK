package com.tsanet.api.attachments.v2;

import java.util.List;

/**
 * What an executed upload produced: the mode that ran, the bytes sent, and in multipart mode
 * the provider's receipts in part order. Feeds {@link AttachmentCompleteRequest}.
 */
public record UploadReceipts(String mode, long bytesSent, List<AttachmentCompleteRequest.PartReceipt> parts) {
    public UploadReceipts {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
