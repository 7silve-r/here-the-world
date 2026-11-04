package com.silver.backend.business.dto;

import com.silver.backend.common.ApiException;

import java.util.List;

public record ApiError(String code,
                       String message,
                       String requestId,
                       List<ApiException.Detail> details) {
}
