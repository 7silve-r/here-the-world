package com.silver.backend.business.dto;

import java.util.List;

public record WorkDetail(
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
        String publishedAt,
        String body,
        List<Asset> assets,
        Asset coverAsset,
        MusicLink musicLink,
        String updatedAt,
        String failureCode) {
}
