package com.silver.backend.common;

public record Reply<T>(int status, T data, String etag) {
    public static <T> Reply<T> ok(T data) {
        return new Reply<>(200, data, null);
    }

    public static <T> Reply<T> of(int status, T data) {
        return new Reply<>(status, data, null);
    }

    public static <T> Reply<T> work(int status, T data, long version) {
        return new Reply<>(status, data, "\"" + version + "\"");
    }
}
