package com.silver.backend.business.dto;

public record Csrf(String csrfToken, String expiresAt) {
}
