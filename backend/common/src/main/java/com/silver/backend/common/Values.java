package com.silver.backend.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Values {
    private Values() {
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static String time(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    public static String time(Long millis) {
        return millis ==  null ? null : time(millis.longValue());
    }

    public static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    public static String excerpt(String value, int max) {
        return length(value) <= max ?
                value:
                value.substring(0, value.offsetByCodePoints(0, max));
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
