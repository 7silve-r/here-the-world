package com.silver.backend.business.feed;

import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public class Pages {
    public record Position(long time, String id) {}

    @Autowired
    private Crypto crypto;

    public int limit(Integer value) {
        int limit = value == null ? 24 : value;
        if (limit < 1 || limit > 50) {
            throw new ApiException(400, "BAD_REQUEST","limit 应为 1-50");
        }
        return limit;
    }

    public String cursor(String route, String filter, long time, String id) {
        return crypto.ticket(
                "cursor",
                Values.map("route", route, "filter", filter, "time", time, "id", id),
                86_400_000);
    }

    public Map<String, Object> open(String cursor, String route, String filter) {
        if (cursor.length() > 4096) {
            throw new ApiException(400, "INVALID_CURSOR", "游标过长");
        }
        var p = crypto.open("cursor", cursor, "INVALID_CURSOR", !"/feed".equals(route));
        if (!Objects.equals(p.get("route"), route) || !Objects.equals(p.get("filter"), filter)) {
            throw new ApiException(400, "INVALID_CURSOR", "游标不能用于该列表或过滤条件");
        }
        return p;
    }

    public Position position(String cursor, String route, String filter, boolean ascending) {
        if (cursor == null) {
            return new Position(ascending ? -1 : Long.MAX_VALUE, ascending ? "" : "~");
        }
        var p = open(cursor, route, filter);
        return new Position(((Number) p.get("time")).longValue(), (String) p.get("id"));
    }

    // 专门为 /feed 作品流生成下一页游标(cursor)
    public String feed(String session, String position) {
        return crypto.ticket(
                "cursor",
                Values.map("route", "/feed", "filter", "", "session", session, "position", position),
                86_400_000);
    }
}
