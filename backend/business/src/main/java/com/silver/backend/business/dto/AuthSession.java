package com.silver.backend.business.dto;

public record AuthSession (String accessToken, String tokenType, Long expiresIn, Me user) {
}
