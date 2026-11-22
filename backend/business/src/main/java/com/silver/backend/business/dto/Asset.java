package com.silver.backend.business.dto;

public record Asset(
        String id,
        String purpose,
        String mediaType,
        String status,
        String mimeType,
        Long sizeBytes,
        String url,
        String thumbnailUrl,
        Long width,
        Long height,
        Long durationMs,
        String failureCode,
        String createdAt) {
}