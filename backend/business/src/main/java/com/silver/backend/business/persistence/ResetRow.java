package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class ResetRow {
    private String tokenHash,
            userId;
    private long expiresAt;
    private boolean consumed;
}
