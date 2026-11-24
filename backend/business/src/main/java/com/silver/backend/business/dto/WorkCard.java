package com.silver.backend.business.dto;

public record WorkCard(
        String id,
        String kind,
        String status,
        Long version,
        String title,
        String excerpt,
        PublicUser author,
        Cover cover,
        WorkStats stats,
        ViewerState viewer,
        String createdAt,
        String publishedAt) {
}
