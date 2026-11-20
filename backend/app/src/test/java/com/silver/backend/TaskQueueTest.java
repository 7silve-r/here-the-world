package com.silver.backend;

import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.TaskQueue;
import com.silver.backend.infrastructure.TransactionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TaskQueueTest extends BackendTestSupport {
    @Autowired private TaskQueue queue;
    @Autowired private TaskQueue.TaskMapper mapper;
    @Autowired private TransactionHelper tx;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearTasks() {
        jdbc.update("DELETE FROM background_tasks");
    }

    @Test
    void executesOnce() {
        var calls = new AtomicInteger();
        queue.register(handler("TEST_SUCCESS", payload -> {
            assertEquals(Map.of("body", "邮件正文"), payload);
            assertTrue(tx.write(queue::ownsExecution));
            calls.incrementAndGet();
        }));
        String id = queue.enqueue("TEST_SUCCESS", Map.of("body", "邮件正文"));
        assertFalse(mapper.get(id).payload.contains("邮件正文"));

        queue.tick();
        queue.tick();

        assertEquals(1, calls.get());
        TaskQueue.Task task = mapper.get(id);
        assertEquals("DONE", task.state);
        assertEquals("", task.payload);
        assertNotNull(task.finishedAt);
        assertNull(task.leaseToken);
        assertThrows(IllegalStateException.class, queue::executionId);
    }

    @Test
    void defersDependency() {
        queue.register(handler("TEST_PENDING", payload -> { throw new TaskQueue.Pending(); }));
        String id = queue.enqueue("TEST_PENDING", Map.of());
        long before = Values.now();
        queue.tick();

        TaskQueue.Task task = mapper.get(id);
        assertEquals("PENDING", task.state);
        assertEquals(0, task.attempts);
        assertTrue(task.availableAt >= before + 2_000);
        assertNull(task.finishedAt);
    }

    @Test
    void stopsAfterRetryLimit() {
        queue.register(handler("TEST_FAILURE", payload -> {
            throw new IllegalStateException("敏感异常文本");
        }));
        String id = queue.enqueue("TEST_FAILURE", Map.of());
        jdbc.update("UPDATE background_tasks SET attempts = 7 WHERE id = ?", id);
        queue.tick();

        TaskQueue.Task task = mapper.get(id);
        assertEquals("FAILED", task.state);
        assertEquals(8, task.attempts);
        assertEquals("IllegalStateException", task.lastError);
        assertNotNull(task.finishedAt);
    }

    @Test
    void fencesOldLease() {
        String id = queue.enqueue("TEST_LEASE", Map.of());
        long now = Values.now();
        assertEquals(1, mapper.claim(id, "old-token", now - 1, now));
        queue.register(handler("TEST_LEASE", payload -> {
            assertNotEquals("old-token", queue.executionId());
            assertEquals(0, mapper.finish(id, "old-token", "DONE", now, null, 0, now));
            assertTrue(tx.write(queue::ownsExecution));
        }));

        queue.tick();
        assertEquals("DONE", mapper.get(id).state);
    }

    @Test
    void cleansOnlyOldFinished() {
        long now = Values.now();
        String oldDone = fixture("DONE", now - 8 * 86_400_000L);
        String recentDone = fixture("DONE", now - 86_400_000L);
        String oldFailed = fixture("FAILED", now - 31 * 86_400_000L);
        String recentFailed = fixture("FAILED", now - 8 * 86_400_000L);
        String pending = fixture("PENDING", null);

        queue.cleanup();

        assertNull(mapper.get(oldDone));
        assertNull(mapper.get(oldFailed));
        assertNotNull(mapper.get(recentDone));
        assertNotNull(mapper.get(recentFailed));
        assertNotNull(mapper.get(pending));
    }

    private String fixture(String state, Long finishedAt) {
        String id = queue.enqueue("TEST_KEEP", Map.of());
        jdbc.update("UPDATE background_tasks SET state = ?, finished_at = ? WHERE id = ?",
                state, finishedAt, id);
        return id;
    }

    private TaskQueue.Handler handler(String kind, Consumer<Map<String, Object>> action) {
        return new TaskQueue.Handler() {
            @Override public String kind() { return kind; }
            @Override public void execute(Map<String, Object> payload) { action.accept(payload); }
        };
    }
}
