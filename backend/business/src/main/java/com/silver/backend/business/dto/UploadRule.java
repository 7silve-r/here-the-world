package com.silver.backend.business.dto;

import java.util.List;

public record UploadRule(String purpose, String mediaType, List<String> allowedMimeTypes, Long maxBytes) {
}
