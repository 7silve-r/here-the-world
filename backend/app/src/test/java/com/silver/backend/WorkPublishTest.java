package com.silver.backend;

import com.silver.backend.business.Views;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class WorkPublishTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/works/";
    private static final long CREATED = 1_700_000_000_000L;

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private TaskQueue queue;
    @Autowired private TaskQueue.TaskMapper tasks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private WorkService service;
    @MockitoSpyBean private Views views;

    private final List<String> userIds = new ArrayList<>();

    @BeforeEach
    void clearTasks() {
        jdbc.update("DELETE FROM background_tasks");
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM background_tasks");
        for (String user : userIds) {
            for (String work : jdbc.queryForList("SELECT id FROM works WHERE owner_id = ?", String.class, user)) {
                jdbc.update("DELETE FROM work_assets WHERE work_id = ?", work);
                jdbc.update("DELETE FROM works WHERE id = ?", work);
            }
        }
        for (String user : userIds) {
            jdbc.update("DELETE FROM assets WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void publishesText() throws Exception {
        Fixture fixture = fixture("TEXT");
        assertError(read(fixture, null), 404, "WORK_NOT_FOUND");
        long start = Values.now();

        Map<String, Object> result = work(publish(fixture, "\"v7\""), 200, "PUBLISHED", 8);

        WorkRow after = works.get(fixture.work().getId());
        assertEquals("PUBLISHED", after.getStatus());
        assertEquals(8L, after.getVersion());
        assertTrue(after.getPublishedAt() >= start && after.getPublishedAt() <= Values.now());
        assertTrue(after.getUpdatedAt() >= start && after.getUpdatedAt() <= Values.now());
        assertEquals(CREATED, after.getCreatedAt());
        assertEquals(fixture.work().getTitle(), after.getTitle());
        assertEquals(fixture.work().getBody(), after.getBody());
        assertEquals(Values.time(after.getPublishedAt()), result.get("publishedAt"));
        assertNull(after.getPublishTaskId());
        assertNull(after.getFailureCode());
        assertEquals(0, taskCount());
        Map<String, Object> publicView = work(read(fixture, null), 200, "PUBLISHED", 8);
        assertEquals(result.get("body"), publicView.get("body"));
        assertEquals(Boolean.FALSE, object(publicView.get("viewer")).get("isOwner"));
    }

    @Test
    void readyCover() throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "READY");

        Map<String, Object> result = work(publish(fixture, "\"v7\""), 200, "PUBLISHED", 8);

        assertEquals(cover, object(result.get("coverAsset")).get("id"));
        assertTrue(((String) object(result.get("cover")).get("url")).contains("/media/public/"));
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t "})
    void blankBody(String body) throws Exception {
        Fixture fixture = fixture("TEXT");
        jdbc.update("UPDATE works SET body = ? WHERE id = ?", body, fixture.work().getId());
        WorkRow before = works.get(fixture.work().getId());

        MockHttpServletResponse response = publish(fixture, "\"v7\"");

        assertError(response, 422, "VALIDATION_FAILED");
        List<?> details = (List<?>) json.readMap(response.getContentAsString()).get("details");
        assertEquals("body", object(details.get(0)).get("field"));
        assertEquals("REQUIRED", object(details.get(0)).get("reason"));
        assertEquals(before, works.get(before.getId()));
        assertEquals(0, taskCount());
    }

    @Test
    void missingMatch() throws Exception {
        Fixture fixture = fixture("TEXT");

        assertError(publish(fixture, null), 428, "PRECONDITION_REQUIRED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v7", "W/\"v7\"", "*", "\"v9223372036854775808\""})
    void badMatch(String match) throws Exception {
        Fixture fixture = fixture("TEXT");

        assertError(publish(fixture, match), 400, "BAD_REQUEST");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"v6\"", "\"v8\""})
    void wrongVersion(String match) throws Exception {
        Fixture fixture = fixture("TEXT");

        assertError(publish(fixture, match), 412, "VERSION_CONFLICT");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(0, taskCount());
    }

    @Test
    void repeatsPublished() throws Exception {
        Fixture fixture = fixture("TEXT");
        work(publish(fixture, "\"v7\""), 200, "PUBLISHED", 8);
        WorkRow before = works.get(fixture.work().getId());

        work(publish(fixture, "\"v8\""), 200, "PUBLISHED", 8);
        assertError(publish(fixture, "\"v7\""), 412, "VERSION_CONFLICT");

        assertEquals(before, works.get(before.getId()));
        assertEquals(0, taskCount());
    }

    @Test
    void failedState() throws Exception {
        Fixture fixture = fixture("TEXT");
        jdbc.update("UPDATE works SET status = 'FAILED', failure_code = 'MEDIA_PROCESSING_FAILED' WHERE id = ?",
                fixture.work().getId());
        WorkRow before = works.get(fixture.work().getId());

        assertError(publish(fixture, "\"v7\""), 409, "WORK_NOT_EDITABLE");

        assertEquals(before, works.get(before.getId()));
        assertEquals(0, taskCount());
    }

    @Test
    void foreignWork() throws Exception {
        Fixture fixture = fixture("TEXT");

        assertError(perform(request(account().access(), fixture.work().getId(), "\"v7\"")),
                404, "WORK_NOT_FOUND");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(0, taskCount());
    }

    @Test
    void missingWork() throws Exception {
        Account owner = account();

        assertError(perform(request(owner.access(), Values.id(), "\"v7\"")), 404, "WORK_NOT_FOUND");

        assertEquals(0, taskCount());
    }

    @Test
    void needsLogin() throws Exception {
        Fixture fixture = fixture("TEXT");

        assertError(perform(request(null, fixture.work().getId(), "\"v7\"")), 401, "AUTH_REQUIRED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "revoked"})
    void badBearer(String kind) throws Exception {
        Fixture fixture = fixture("TEXT");
        String token = "invalid-token";
        String code = "TOKEN_INVALID";
        if ("revoked".equals(kind)) {
            token = fixture.owner().access();
            code = "SESSION_REVOKED";
            jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", fixture.owner().session().getId());
        }

        assertError(perform(request(token, fixture.work().getId(), "\"v7\"")), 401, code);

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "purpose", "type", "CREATED", "FAILED", "cleaned", "missingFile"})
    void badCover(String kind) throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "READY");
        String code = "ASSET_NOT_READY";
        switch (kind) {
            case "foreign" -> {
                jdbc.update("UPDATE assets SET owner_id = ? WHERE id = ?", account().user(), cover);
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "purpose" -> {
                jdbc.update("UPDATE assets SET purpose = 'AVATAR' WHERE id = ?", cover);
                code = "ASSET_TYPE_MISMATCH";
            }
            case "type" -> {
                jdbc.update("UPDATE assets SET media_type = 'VIDEO' WHERE id = ?", cover);
                code = "ASSET_TYPE_MISMATCH";
            }
            case "cleaned" -> jdbc.update("UPDATE assets SET cleaned_at = ? WHERE id = ?", Values.now(), cover);
            case "missingFile" -> jdbc.update("UPDATE assets SET storage_key = NULL WHERE id = ?", cover);
            default -> jdbc.update("UPDATE assets SET status = ? WHERE id = ?", kind, cover);
        }

        assertError(publish(fixture, "\"v7\""), 422, code);

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "AUDIO", "MUSIC_LINK"})
    void missingMedia(String kind) throws Exception {
        Fixture fixture = fixture(kind);

        assertError(publish(fixture, "\"v7\""), 422, "VALIDATION_FAILED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADED", "PROCESSING"})
    void acceptsPending(String state) throws Exception {
        Fixture fixture = fixture("TEXT");
        cover(fixture, state);

        Map<String, Object> result = work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);

        WorkRow after = works.get(fixture.work().getId());
        TaskQueue.Task task = onlyTask();
        assertEquals("PUBLISH", task.kind);
        assertEquals("PENDING", task.state);
        assertEquals(0, task.attempts);
        assertNull(after.getPublishedAt());
        assertNull(result.get("publishedAt"));
        assertNotNull(after.getPublishTaskId());
        assertNotEquals(task.id, after.getPublishTaskId());
        assertEquals(Values.map("work", after.getId(), "job", after.getPublishTaskId()), payload(task));
        assertFalse(task.payload.contains(after.getId()));
        assertError(read(fixture, null), 404, "WORK_NOT_FOUND");

        work(publish(fixture, "\"v8\""), 202, "PROCESSING", 8);
        assertError(publish(fixture, "\"v7\""), 412, "VERSION_CONFLICT");
        assertEquals(after, works.get(after.getId()));
        assertEquals(1, taskCount());
        assertEquals(task.payload, tasks.get(task.id).payload);
    }

    @Test
    void waitsThenPublishes() throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        WorkRow before = works.get(fixture.work().getId());
        TaskQueue.Task task = onlyTask();
        long start = Values.now();

        // 调用真实队列及已注册的 PUBLISH 处理器。
        queue.tick();

        TaskQueue.Task waiting = tasks.get(task.id);
        assertEquals("PENDING", waiting.state);
        assertEquals(0, waiting.attempts);
        assertTrue(waiting.availableAt >= start + 2_000);
        assertNull(waiting.lastError);
        assertNull(waiting.finishedAt);
        assertNull(waiting.leaseToken);
        assertEquals(0L, waiting.leaseUntil);
        assertEquals(task.payload, waiting.payload);
        assertEquals(before, works.get(before.getId()));
        work(read(fixture, fixture.owner().access()), 200, "PROCESSING", 8);

        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", cover);
        // 调整数据库时间验证调度资格，不等待两秒。
        jdbc.update("UPDATE background_tasks SET available_at = ? WHERE id = ?", Values.now() + 60_000, task.id);
        queue.tick();
        assertEquals(before, works.get(before.getId()));
        due(task.id);
        queue.tick();

        Map<String, Object> result = work(read(fixture, null), 200, "PUBLISHED", 9);
        assertNotNull(result.get("publishedAt"));
        assertNull(result.get("failureCode"));
        assertDone(task.id);
        WorkRow published = works.get(before.getId());
        queue.tick();
        assertEquals(published, works.get(before.getId()));
    }

    @Test
    void mediaFailure() throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();
        jdbc.update("UPDATE assets SET status = 'FAILED' WHERE id = ?", cover);

        queue.tick();

        Map<String, Object> result = work(read(fixture, fixture.owner().access()), 200, "FAILED", 9);
        assertEquals("MEDIA_PROCESSING_FAILED", result.get("failureCode"));
        assertNull(result.get("publishedAt"));
        assertDone(task.id);
        assertError(read(fixture, null), 404, "WORK_NOT_FOUND");
        assertError(publish(fixture, "\"v9\""), 409, "WORK_NOT_EDITABLE");

        Map<String, Object> input = Values.map("kind", "TEXT", "title", "修正后的草稿", "body", "已移除失败封面",
                "mediaAssetIds", List.of(), "coverAssetId", null, "musicLinkId", null);
        work(perform(put(PATH + fixture.work().getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.owner().access())
                .header(HttpHeaders.IF_MATCH, "\"v9\"")
                .contentType(MediaType.APPLICATION_JSON).content(json.write(input))), 200, "DRAFT", 10);
        Map<String, Object> recovered = work(publish(fixture, "\"v10\""), 200, "PUBLISHED", 11);
        assertNull(recovered.get("failureCode"));
        assertNull(works.get(fixture.work().getId()).getPublishTaskId());
        assertEquals(1, taskCount());
    }

    @Test
    void waitsForAll() throws Exception {
        Fixture fixture = fixture("IMAGE");
        String first = asset(fixture.owner(), "WORK_MEDIA", "READY");
        String second = asset(fixture.owner(), "WORK_MEDIA", "PROCESSING");
        works.bind(fixture.work().getId(), first, 0);
        works.bind(fixture.work().getId(), second, 1);
        cover(fixture, "READY");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();

        queue.tick();

        assertEquals("PROCESSING", works.get(fixture.work().getId()).getStatus());
        assertEquals("PENDING", tasks.get(task.id).state);
        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", second);
        due(task.id);
        queue.tick();

        Map<String, Object> result = work(read(fixture, null), 200, "PUBLISHED", 9);
        List<?> media = (List<?>) result.get("assets");
        assertEquals(List.of(first, second), media.stream().map(item -> object(item).get("id")).toList());
        assertEquals(List.of(first, second), works.assets(fixture.work().getId()));
        assertDone(task.id);
    }

    @Test
    void staleJob() throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();
        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", cover);
        jdbc.update("UPDATE works SET publish_task_id = ? WHERE id = ?", Values.id(), fixture.work().getId());
        WorkRow before = works.get(fixture.work().getId());

        queue.tick();

        assertEquals(before, works.get(before.getId()));
        assertDone(task.id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED", "FAILED"})
    void staleState(String state) throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();
        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", cover);
        jdbc.update("UPDATE works SET status = ? WHERE id = ?", state, fixture.work().getId());
        WorkRow before = works.get(fixture.work().getId());

        queue.tick();

        assertEquals(before, works.get(before.getId()));
        assertDone(task.id);
    }

    @Test
    void deletedWork() throws Exception {
        Fixture fixture = fixture("TEXT");
        cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();
        jdbc.update("DELETE FROM works WHERE id = ?", fixture.work().getId());

        queue.tick();

        assertNull(works.get(fixture.work().getId()));
        assertDone(task.id);
    }

    @Test
    void needsExecution() throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();
        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", cover);
        WorkRow before = works.get(fixture.work().getId());

        service.finishPublish(payload(task));

        assertEquals(before, works.get(before.getId()));
        assertEquals("PENDING", tasks.get(task.id).state);
        queue.tick();
        work(read(fixture, null), 200, "PUBLISHED", 9);
        assertDone(task.id);
    }

    @Test
    void lostLease() throws Exception {
        Fixture fixture = fixture("TEXT");
        String cover = cover(fixture, "PROCESSING");
        work(publish(fixture, "\"v7\""), 202, "PROCESSING", 8);
        TaskQueue.Task task = onlyTask();
        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", cover);
        WorkRow before = works.get(fixture.work().getId());
        var replacement = new AtomicReference<String>();
        doAnswer(invocation -> {
            // 在旧执行者进入结果事务前，用真实 SQL 模拟另一执行者接管过期租约。
            String token = Values.id();
            replacement.set(token);
            assertNotEquals(queue.executionId(), token);
            jdbc.update("UPDATE background_tasks SET lease_until = ? WHERE id = ?", Values.now() - 1, task.id);
            assertEquals(1, tasks.claim(task.id, token, Values.now() + 60_000, Values.now()));
            return invocation.callRealMethod();
        }).when(service).finishPublish(anyMap());

        queue.tick();

        assertEquals(before, works.get(before.getId()));
        assertEquals("RUNNING", tasks.get(task.id).state);
        assertEquals(replacement.get(), tasks.get(task.id).leaseToken);
        assertNull(tasks.get(task.id).finishedAt);
        doCallRealMethod().when(service).finishPublish(anyMap());
        jdbc.update("UPDATE background_tasks SET lease_until = ? WHERE id = ?", Values.now() - 1, task.id);
        queue.tick();
        work(read(fixture, null), 200, "PUBLISHED", 9);
        assertDone(task.id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "PROCESSING"})
    void rollsBack(String state) throws Exception {
        Fixture fixture = fixture("TEXT");
        cover(fixture, state);
        doAnswer(invocation -> {
            assertEquals(8L, works.get(fixture.work().getId()).getVersion());
            assertEquals("PROCESSING".equals(state) ? 1 : 0, taskCount());
            throw new IllegalStateException("Failure after publication update");
        }).when(views).detail(any(WorkRow.class), any(Viewer.class));

        assertError(publish(fixture, "\"v7\""), 500, "INTERNAL_ERROR");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(0, taskCount());
        doCallRealMethod().when(views).detail(any(WorkRow.class), any(Viewer.class));
        work(publish(fixture, "\"v7\""), "PROCESSING".equals(state) ? 202 : 200,
                "PROCESSING".equals(state) ? "PROCESSING" : "PUBLISHED", 8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "PROCESSING"})
    void concurrentPublish(String state) throws Exception {
        Fixture fixture = fixture("TEXT");
        cover(fixture, state);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return publish(fixture, "\"v7\"");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int success = "PROCESSING".equals(state) ? 202 : 200;
            var responses = new ArrayList<MockHttpServletResponse>();
            for (Future<MockHttpServletResponse> future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.getStatus() == success).count());
            assertEquals(1, responses.stream().filter(response -> response.getStatus() == 412).count());
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == success) {
                    work(response, success, success == 202 ? "PROCESSING" : "PUBLISHED", 8);
                } else {
                    assertError(response, 412, "VERSION_CONFLICT");
                }
            }
            assertEquals(8L, works.get(fixture.work().getId()).getVersion());
            assertEquals(success == 202 ? 1 : 0, taskCount());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '发布作者', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "publish_" + user.replace("-", "").substring(0, 12), CREATED);
        userIds.add(user);
        var session = new SessionRow();
        session.setId(Values.id());
        session.setUserId(user);
        session.setRefreshHash(crypto.digest(crypto.randomToken()));
        session.setCreatedAt(Values.now());
        session.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(session);
        return new Account(user, session, crypto.jwt(user, session.getId(), 900));
    }

    private Fixture fixture(String kind) {
        Account owner = account();
        var row = new WorkRow();
        row.setId(Values.id());
        row.setOwnerId(owner.user());
        row.setKind(kind);
        row.setStatus("DRAFT");
        row.setVersion(7);
        row.setTitle("已保存的标题");
        row.setBody("已保存的正文");
        row.setCreatedAt(CREATED);
        row.setUpdatedAt(CREATED + 1000);
        works.insert(row);
        return new Fixture(owner, row);
    }

    private String cover(Fixture fixture, String state) {
        String id = asset(fixture.owner(), "WORK_COVER", state);
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", id, fixture.work().getId());
        fixture.work().setCoverAssetId(id);
        return id;
    }

    private String asset(Account owner, String purpose, String state) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, thumbnail_key, size_bytes, created_at)
                VALUES (?, ?, ?, 'IMAGE', ?, 'image/jpeg', ?, ?, 1024, ?)
                """, id, owner.user(), purpose, state, "original/" + id, "thumbnails/" + id + ".jpg", CREATED);
        return id;
    }

    private int taskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM background_tasks", Integer.class);
    }

    private TaskQueue.Task onlyTask() {
        List<String> ids = jdbc.queryForList("SELECT id FROM background_tasks", String.class);
        assertEquals(1, ids.size());
        return tasks.get(ids.get(0));
    }

    private Map<String, Object> payload(TaskQueue.Task task) {
        return json.readMap(crypto.decrypt(task.payload));
    }

    private void due(String id) {
        jdbc.update("UPDATE background_tasks SET available_at = ? WHERE id = ?", Values.now() - 1, id);
    }

    private void assertDone(String id) {
        TaskQueue.Task task = tasks.get(id);
        assertEquals("DONE", task.state);
        assertEquals(0, task.attempts);
        assertEquals("", task.payload);
        assertNull(task.lastError);
        assertNull(task.leaseToken);
        assertEquals(0L, task.leaseUntil);
        assertNotNull(task.finishedAt);
    }

    private MockHttpServletResponse publish(Fixture fixture, String match) throws Exception {
        return perform(request(fixture.owner().access(), fixture.work().getId(), match));
    }

    private MockHttpServletRequestBuilder request(String token, String id, String match) {
        var request = post(PATH + id + "/publish");
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (match != null) request.header(HttpHeaders.IF_MATCH, match);
        return request;
    }

    private MockHttpServletResponse read(Fixture fixture, String token) throws Exception {
        var request = get(PATH + fixture.work().getId());
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return perform(request);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "work-publish-" + Values.id()))
                .andReturn().getResponse();
    }

    private Map<String, Object> work(MockHttpServletResponse response, int status, String state, long version)
            throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertEquals("\"v" + version + "\"", response.getHeader(HttpHeaders.ETAG));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-publish-"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        Map<String, Object> result = object(body.get("data"));
        assertEquals(state, result.get("status"));
        assertEquals(version, ((Number) result.get("version")).longValue());
        return result;
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertFalse(body.containsKey("data"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private record Account(String user, SessionRow session, String access) {}
    private record Fixture(Account owner, WorkRow work) {}
}
