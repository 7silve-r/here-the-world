package com.silver.backend.common;

import lombok.Getter;

import java.util.List;

@Getter
public class ApiException extends RuntimeException{
    public record Detail(String field, String reason, String message){}

    private final int status;
    private final String code;
    private final List<Detail>  details;
    private final Integer retryAfter;

    public ApiException(int status, String code, String message, List<Detail> details, Integer retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.retryAfter = retryAfter;
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    // 常用异常的包装
    public static ApiException invalid(String field, String reason, String message) {
        return new ApiException(
                422,
                "VALIDATION_FAILED",
                message,
                List.of(new Detail(field, reason, message)),
                null
        );
    }
}
