package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class UserRow {
    private String id,
            email,
            username,
            passwordHash,
            displayName,
            bio,
            avatarAssetId,
            theme,
            motion;
    private boolean emailVerified,
            autoPlayMedia;
    private long createdAt;
}
