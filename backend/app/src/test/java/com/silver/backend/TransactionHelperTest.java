package com.silver.backend;

import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.TransactionHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class TransactionHelperTest extends BackendTestSupport {
    @Autowired private TransactionHelper tx;
    @Autowired private Crypto crypto;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void rollsBackWrites() {
        String id = Values.id();
        assertThrows(IllegalStateException.class, () -> tx.run("10:test:" + id, () -> {
            insertCounter(id);
            throw new IllegalStateException("fixture failure");
        }));
        assertEquals(0, records("rate_limits", "id", id));

        tx.run("10:test:" + id, () -> insertCounter(id));
        assertEquals(1, records("rate_limits", "id", id));
    }

    @Test
    void rejectsReverseOrder() {
        String suffix = Values.id();
        String first = "10:test:" + suffix;
        String second = "20:test:" + suffix;
        assertThrows(IllegalStateException.class, () -> tx.run(second, () -> tx.lock(List.of(first))));
        assertEquals(0, records("resource_locks", "lock_key", crypto.digest(second)));

        tx.run(List.of(second, first), () -> tx.lock(List.of(first)));
        assertEquals(1, records("resource_locks", "lock_key", crypto.digest(first)));
        assertEquals(1, records("resource_locks", "lock_key", crypto.digest(second)));
    }

    @Test
    void waitsForOwner() throws Exception {
        String id = Values.id();
        String resource = "10:test:" + id;
        var pool = Executors.newFixedThreadPool(2);
        var ownerHasLock = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var owner = pool.submit(() -> tx.run(resource, () -> {
                insertCounter(id);
                ownerHasLock.countDown();
                await(release);
            }));
            await(ownerHasLock);
            var writer = pool.submit(() -> {
                writerStarted.countDown();
                return tx.write(resource, () -> records("rate_limits", "id", id));
            });
            await(writerStarted);
            assertThrows(TimeoutException.class, () -> writer.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            owner.get(5, TimeUnit.SECONDS);
            assertEquals(1, writer.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cleansOldLocks() {
        String old = crypto.digest(Values.id());
        String recent = crypto.digest(Values.id());
        jdbc.update("INSERT INTO resource_locks (lock_key, touched_at) VALUES (?, ?)", old, 1L);
        jdbc.update("INSERT INTO resource_locks (lock_key, touched_at) VALUES (?, ?)", recent, Values.now());
        tx.cleanupLocks();
        assertEquals(0, records("resource_locks", "lock_key", old));
        assertEquals(1, records("resource_locks", "lock_key", recent));
    }

    private void insertCounter(String id) {
        jdbc.update("INSERT INTO rate_limits (id, hits, expires_at) VALUES (?, 1, ?)",
                id, Values.now() + 60_000);
    }

    private int records(String table, String column, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "等待另一个事务超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
