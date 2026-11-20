package com.silver.backend.business.dto;

public record Csrf(String token, String expiresAt) {
}
