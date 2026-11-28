package com.silver.backend.business.dto;

public record UploadInput(String purpose, String fileName, String contentType, Long sizeBytes) {
}
