package com.silver.backend.business.dto;

public record ChangePasswordInput (String currentPassword, String newPassword) {
}
