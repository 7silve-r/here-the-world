package com.silver.backend.business.dto;

import java.util.List;

public record Capabilities(
        List<UploadRule> uploadRules,
        Long maxImagesPerWork,
        Long maxTitleLength,
        Long maxBodyLength,
        Long maxCommentLength,
        Long verificationCodeTtlSeconds,
        Long resendAfterSeconds,
        List<String> musicEmbedProviders) {
}
