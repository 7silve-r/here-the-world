package com.silver.backend.business.dto;

public record MessageAccepted (Boolean accepted, Long resendAfterSeconds) {
}
