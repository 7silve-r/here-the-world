package com.silver.backend.common;

public record Viewer(String userId, String sessionId) {
    public static Viewer guest() {
        return new Viewer(null, null);
    }

    public boolean authenticated() {
        return userId != null;
    }

    public String requireUser() {
        if (!authenticated()) {
            throw new ApiException(401, "AUTH_REQUIRED", "请先登录");
        }
        return userId;
    }
}
