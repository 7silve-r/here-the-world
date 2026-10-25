package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class WorkRow {
    private String id,
            ownerId,
            kind,
            status,
            title,
            body,
            coverAssetId,
            musicLinkId,
            failureCode,
            publishTaskId;
    private long version,
            createdAt,
            updatedAt;
    private Long publishedAt;
}
