package com.tsanet.api.attachments.v2;

import java.util.List;

/** One page of a case's grants, mapped from the generated {@code AttachmentGrantPageDTO}. */
public record AttachmentGrantPage(List<AttachmentGrant> content, long totalElements, int totalPages, int size, int number) {

    public AttachmentGrantPage {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
