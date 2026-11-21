package com.silver.backend.business.dto;

public record ResetPasswordInput (String token, String newPassword) {
}
