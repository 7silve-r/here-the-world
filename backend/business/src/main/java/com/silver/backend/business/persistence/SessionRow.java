package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class SessionRow {
    private String id,
            userId,
            refreshHash;
    private long expiresAt,
            createdAt;
    private boolean revoked;
}
