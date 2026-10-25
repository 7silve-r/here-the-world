package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class AssetRow {
    private String id,
            ownerId,
            purpose,
            mediaType,
            status,
            mimeType,
            storageKey,
            playbackKey,
            thumbnailKey,
            failureCode;
    private long sizeBytes,
            createdAt;
    private Integer width,
            height;
    private Long durationMs,
            cleanedAt;
}
