package com.silver.backend.business.dto;

public record Me (
        String id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        Long publishedWorkCount,
        String createdAt,
        String email,
        Boolean emailVerified
) {
}
