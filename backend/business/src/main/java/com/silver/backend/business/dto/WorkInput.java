package com.silver.backend.business.dto;

import java.util.List;

public record WorkInput(
        String kind,
        String title,
        String body,
        List<String> mediaAssetIds,
        String coverAssetId,
        String musicLinkId) {
}
