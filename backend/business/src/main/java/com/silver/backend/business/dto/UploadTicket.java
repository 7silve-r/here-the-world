package com.silver.backend.business.dto;

import java.util.Map;

public record UploadTicket(
        String uploadId,
        String assetId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        String expiresAt,
        Long maxBytes) {
}
