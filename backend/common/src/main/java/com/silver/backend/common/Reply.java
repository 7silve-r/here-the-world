package com.silver.backend.common;

public record Reply(int status, Object data, String etag) {
    public static Reply ok(Object data) {
        return new Reply(200, data, null);
    }

    public static Reply of(int status, Object data) {
        return new Reply(status, data, null);
    }

    public static Reply work(int status, Object data, long version) {
        return new Reply(status, data, "\"v" + version + "\"");
    }
}
