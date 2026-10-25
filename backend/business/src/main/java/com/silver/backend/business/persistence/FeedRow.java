package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class FeedRow {
    private String id;
    private long cutoff,
            expiresAt;
}
