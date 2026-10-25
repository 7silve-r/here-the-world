package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class CodeRow {
    private String userId,
            codeHash;
    private long expiresAt,
            sentAt;
    private int attempts;
}
