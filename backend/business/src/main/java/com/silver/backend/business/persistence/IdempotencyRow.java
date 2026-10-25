package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class IdempotencyRow {
    private String id,
            bodyHash,
            responseJson,
            leaseToken;
    private long expiresAt,
            leaseUntil;
}
