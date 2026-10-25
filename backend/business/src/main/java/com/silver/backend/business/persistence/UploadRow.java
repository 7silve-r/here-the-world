package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class UploadRow {
    private String id,
            assetId,
            ownerId,
            state;
    private long expiresAt,
            createdAt,
            expectedSize;
    private Long finishedAt;
}
