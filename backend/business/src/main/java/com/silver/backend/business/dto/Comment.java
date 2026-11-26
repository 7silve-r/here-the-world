package com.silver.backend.business.dto;

public record Comment(
        String id,
        String workId,
        PublicUser author,
        String body,
        Boolean canDelete,
        String createdAt) {
}
