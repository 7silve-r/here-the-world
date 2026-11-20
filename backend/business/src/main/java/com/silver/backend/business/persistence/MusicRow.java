package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class MusicRow {
    private String id,
            ownerId,
            provider,
            sourceUrl,
            title,
            artist,
            coverUrl,
            metadataStatus;
    private long createdAt;
}
