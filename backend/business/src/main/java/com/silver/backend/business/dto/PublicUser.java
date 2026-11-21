package com.silver.backend.business.dto;

public record PublicUser (
        String id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        Long publishedWorkCount,
        String createdAt
) {
}
