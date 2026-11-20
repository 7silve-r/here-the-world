package com.silver.backend;

import com.silver.backend.business.persistence.mapper.ControlMapper;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RequestControlTest extends BackendTestSupport {
    private static final String PATH = "/works";

    @Autowired private RequestControl control;
    @Autowired private ControlMapper mapper;
    @Autowired private Crypto crypto;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void rateWindow() {
        String identity = Values.id();
        control.limit("login-account", identity, 2, 60);
        control.limit("login-account", identity, 2, 60);
        ApiException error = assertThrows(ApiException.class,
                () -> control.limit("login-account", identity, 2, 60));
        assertEquals(429, error.status);
        assertEquals("RATE_LIMITED", error.code);
        assertEquals(60, error.retryAfter);

        control.limit("register-account", identity, 2, 60);
        String id = crypto.keyedDigest("limit", "login-account:" + identity);
        jdbc.update("UPDATE rate_limits SET expires_at = ? WHERE id = ?", Values.now() - 1, id);
        control.limit("login-account", identity, 2, 60);
        assertEquals(1, mapper.rate(id, Values.now()));
    }

    @Test
    void concurrentRate() throws Exception {
        String identity = Values.id();
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        control.limit("login-source", identity, 3, 60);
                        return true;
                    } catch (ApiException e) {
                        assertEquals("RATE_LIMITED", e.code);
                        return false;
                    }
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) allowed++;
            }
            assertEquals(3, allowed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replaysResult() {
        String user = Values.id();
        String key = Values.id();
        var first = control.idempotency(user, PATH, key, Values.map("title", "作品", "kind", "TEXT"),
                () -> Reply.work(201, Map.of("id", "work-private-id"), 1));
        var replay = control.idempotency(user, PATH, key, Values.map("kind", "TEXT", "title", "作品"),
                () -> fail("仅调整字段顺序应重放创建结果"));

        assertEquals(first, replay);
        assertEquals(201, replay.status());
        assertEquals("\"v1\"", replay.etag());
        assertFalse(mapper.idem(recordId(user, key)).getResponseJson().contains("work-private-id"));
    }

    @Test
    void rejectsConflict() {
        String user = Values.id();
        String key = Values.id();
        control.idempotency(user, PATH, key, Map.of("title", "before"), () -> Reply.ok("first"));
        ApiException conflict = assertThrows(ApiException.class, () ->
                control.idempotency(user, PATH, key, Map.of("title", "after"), () -> fail("不应重复执行")));
        assertEquals(409, conflict.status);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code);
        ApiException badKey = assertThrows(ApiException.class, () ->
                control.idempotency(user, PATH, "1-1-1-1-1", Map.of(), () -> fail("不应接受简写 UUID")));
        assertEquals(400, badKey.status);
    }

    @Test
    void abandonsFailure() {
        String user = Values.id();
        String key = Values.id();
        String effect = Values.id();
        assertThrows(IllegalStateException.class, () ->
                control.idempotency(user, PATH, key, Map.of(), () -> {
                    jdbc.update("INSERT INTO rate_limits (id, hits, expires_at) VALUES (?, 1, ?)",
                            effect, Values.now() + 60_000);
                    throw new IllegalStateException("fixture failure");
                }));

        assertNull(mapper.rate(effect, Values.now()));
        assertNull(mapper.idem(recordId(user, key)));
        assertEquals("retried", control.idempotency(user, PATH, key, Map.of(),
                () -> Reply.ok("retried")).data());
    }

    @Test
    void activeClaim() {
        String user = Values.id();
        String key = Values.id();
        String id = recordId(user, key);
        long now = Values.now();
        jdbc.update("""
                INSERT INTO idempotency (id, body_hash, expires_at, lease_until, lease_token)
                VALUES (?, ?, ?, ?, ?)
                """, id, crypto.digest("{}"), now + 90_000, now + 60_000, "old-owner");

        ApiException pending = assertThrows(ApiException.class, () ->
                control.idempotency(user, PATH, key, Map.of(), () -> fail("有效租约期间不应接管")));
        assertEquals("REQUEST_IN_PROGRESS", pending.code);
        assertEquals(2, pending.retryAfter);

        jdbc.update("UPDATE idempotency SET lease_until = ? WHERE id = ?", now - 1, id);
        assertEquals("new-result", control.idempotency(user, PATH, key, Map.of(),
                () -> Reply.ok("new-result")).data());
        assertNotEquals("old-owner", mapper.idem(id).getLeaseToken());
    }

    private String recordId(String user, String key) {
        return crypto.digest(user + "\nPOST\n" + PATH + "\n" + key);
    }
}
