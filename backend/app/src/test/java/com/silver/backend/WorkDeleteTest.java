package com.silver.backend;

import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.common.ApiException;
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
import org.mybatis.spring.SqlSessionTemplate;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class WorkDeleteTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/works/";
    private static final long CREATED = 1_700_000_000_000L;

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private TaskQueue queue;
    @Autowired private TaskQueue.TaskMapper tasks;
    @MockitoSpyBean private WorkMapper works;

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
                jdbc.update("DELETE FROM comments WHERE work_id = ?", work);
                jdbc.update("DELETE FROM bookmarks WHERE work_id = ?", work);
                jdbc.update("DELETE FROM work_likes WHERE work_id = ?", work);
                jdbc.update("DELETE FROM work_assets WHERE work_id = ?", work);
                jdbc.update("DELETE FROM works WHERE id = ?", work);
            }
        }
        for (String user : userIds) {
            jdbc.update("DELETE FROM music_links WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM assets WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PROCESSING", "PUBLISHED", "FAILED"})
    void deletesState(String state) throws Exception {
        Fixture fixture = fixture(account(), state, "TEXT");
        relations(fixture);

        accepted(remove(fixture, "\"v7\""));

        assertDeleted(fixture);
        assertNotNull(users.user(fixture.owner().user()));
        assertEquals(fixture.owner().session(), users.session(fixture.owner().session().getId()));
        assertError(read(fixture, fixture.owner().access()), 404, "WORK_NOT_FOUND");
        assertError(read(fixture, null), 404, "WORK_NOT_FOUND");
    }

    @Test
    void clearsMedia() throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "IMAGE");
        List<String> media = media(fixture);
        String cover = cover(fixture, "READY");
        relations(fixture);
        List<Map<String, Object>> assets = assetRows(fixture.owner());

        accepted(remove(fixture, "\"v7\""));

        assertDeleted(fixture);
        assertEquals(assets, assetRows(fixture.owner()));
        assertEquals(2, media.size());
        assertEquals(3, assets.size());
        assertNotNull(jdbc.queryForObject("SELECT storage_key FROM assets WHERE id = ?", String.class, cover));
    }

    @Test
    void keepsOtherWork() throws Exception {
        Account owner = account();
        Fixture target = fixture(owner, "PUBLISHED", "TEXT");
        Fixture other = fixture(owner, "PUBLISHED", "TEXT");
        relations(target);
        relations(other);
        List<Map<String, Object>> comments = rows("comments", other.work().getId());
        List<Map<String, Object>> likes = rows("work_likes", other.work().getId());
        List<Map<String, Object>> bookmarks = rows("bookmarks", other.work().getId());

        accepted(remove(target, "\"v7\""));

        assertDeleted(target);
        assertEquals(other.work(), works.get(other.work().getId()));
        assertEquals(comments, rows("comments", other.work().getId()));
        assertEquals(likes, rows("work_likes", other.work().getId()));
        assertEquals(bookmarks, rows("bookmarks", other.work().getId()));
        assertEquals(200, read(other, null).getStatus());
    }

    @Test
    void keepsSharedAssets() throws Exception {
        Account owner = account();
        Fixture target = fixture(owner, "PUBLISHED", "IMAGE");
        Fixture other = fixture(owner, "DRAFT", "IMAGE");
        List<String> media = media(target);
        for (int i = 0; i < media.size(); i++) works.bind(other.work().getId(), media.get(i), i);
        String cover = cover(target, "READY");
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", cover, other.work().getId());
        WorkRow before = works.get(other.work().getId());
        List<Map<String, Object>> assets = assetRows(owner);

        accepted(remove(target, "\"v7\""));

        assertDeleted(target);
        assertEquals(before, works.get(other.work().getId()));
        assertEquals(media, works.assets(other.work().getId()));
        assertEquals(assets, assetRows(owner));
    }

    @Test
    void keepsMusic() throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "MUSIC_LINK");
        String music = Values.id();
        jdbc.update("""
                INSERT INTO music_links (id, owner_id, provider, source_url, title, metadata_status, created_at)
                VALUES (?, ?, 'OTHER', 'https://example.test/music', '音乐记录', 'PARTIAL', ?)
                """, music, fixture.owner().user(), CREATED);
        jdbc.update("UPDATE works SET music_link_id = ? WHERE id = ?", music, fixture.work().getId());
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM music_links WHERE id = ?", music);

        accepted(remove(fixture, "\"v7\""));

        assertDeleted(fixture);
        assertEquals(before, jdbc.queryForMap("SELECT * FROM music_links WHERE id = ?", music));
    }

    @Test
    void missingWork() throws Exception {
        Account owner = account();

        assertError(perform(request(owner.access(), Values.id(), "\"v7\"")), 404, "WORK_NOT_FOUND");
    }

    @Test
    void repeatsDelete() throws Exception {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");
        accepted(remove(fixture, "\"v7\""));

        assertError(remove(fixture, "\"v7\""), 404, "WORK_NOT_FOUND");

        assertDeleted(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED"})
    void foreignWork(String state) throws Exception {
        Fixture fixture = fixture(account(), state, "TEXT");
        relations(fixture);
        Snapshot before = snapshot(fixture);

        assertError(perform(request(account().access(), fixture.work().getId(), "\"v7\"")),
                404, "WORK_NOT_FOUND");

        assertEquals(before, snapshot(fixture));
    }

    @Test
    void needsLogin() throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "TEXT");

        assertError(perform(request(null, fixture.work().getId(), "\"v7\"")), 401, "AUTH_REQUIRED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "revoked"})
    void badBearer(String kind) throws Exception {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");
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

    @Test
    void guestService() {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");

        ApiException error = assertThrows(ApiException.class,
                () -> service.delete(Viewer.guest(), fixture.work().getId(), "\"v7\""));

        assertEquals(401, error.status);
        assertEquals("AUTH_REQUIRED", error.code);
        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @Test
    void missingMatch() throws Exception {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");

        assertError(remove(fixture, null), 428, "PRECONDITION_REQUIRED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v7", "\"v0\"", "W/\"v7\"", "*", "\"v9223372036854775808\""})
    void badMatch(String match) throws Exception {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");

        assertError(remove(fixture, match), 400, "BAD_REQUEST");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"v6\"", "\"v8\""})
    void wrongVersion(String match) throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "IMAGE");
        media(fixture);
        relations(fixture);
        Snapshot before = snapshot(fixture);

        assertError(remove(fixture, match), 412, "VERSION_CONFLICT");

        assertEquals(before, snapshot(fixture));
    }

    @Test
    void rollsBack() throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "IMAGE");
        media(fixture);
        relations(fixture);
        Snapshot before = snapshot(fixture);
        WorkMapper actual = sql.getMapper(WorkMapper.class);
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            long expected = invocation.getArgument(1);
            assertRelationsGone(id);
            assertEquals(1, actual.delete(id, expected));
            assertNull(actual.get(id));
            throw new IllegalStateException("Failure after deleting work and relations");
        }).when(works).delete(anyString(), anyLong());

        assertError(remove(fixture, "\"v7\""), 500, "INTERNAL_ERROR");

        assertEquals(before, snapshot(fixture));
        // 恢复真实 Mapper 调用，确认回滚后仍可正常删除。
        doAnswer(invocation -> actual.delete(invocation.getArgument(0), invocation.getArgument(1)))
                .when(works).delete(anyString(), anyLong());
        accepted(remove(fixture, "\"v7\""));
        assertDeleted(fixture);
    }

    @Test
    void versionChanged() throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "IMAGE");
        media(fixture);
        relations(fixture);
        Snapshot before = snapshot(fixture);
        WorkMapper actual = sql.getMapper(WorkMapper.class);
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            long expected = invocation.getArgument(1);
            assertRelationsGone(id);
            // 注入版本变化，让真实的条件 DELETE 返回 0，检查之前的关系删除也回滚。
            jdbc.update("UPDATE works SET version = version + 1 WHERE id = ?", id);
            int changed = actual.delete(id, expected);
            assertEquals(0, changed);
            return changed;
        }).when(works).delete(anyString(), anyLong());

        assertError(remove(fixture, "\"v7\""), 412, "VERSION_CONFLICT");

        assertEquals(before, snapshot(fixture));
    }

    @Test
    void concurrentDelete() throws Exception {
        Fixture fixture = fixture(account(), "PUBLISHED", "IMAGE");
        media(fixture);
        relations(fixture);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return remove(fixture, "\"v7\"");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var responses = new ArrayList<MockHttpServletResponse>();
            for (Future<MockHttpServletResponse> future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.getStatus() == 200).count());
            assertEquals(1, responses.stream().filter(response -> response.getStatus() == 404).count());
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == 200) accepted(response);
                else assertError(response, 404, "WORK_NOT_FOUND");
            }
            assertDeleted(fixture);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void queuedPublish() throws Exception {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");
        String cover = cover(fixture, "PROCESSING");
        MockHttpServletResponse pending = perform(post(PATH + fixture.work().getId() + "/publish")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.owner().access())
                .header(HttpHeaders.IF_MATCH, "\"v7\""));
        assertEquals(202, pending.getStatus(), pending.getContentAsString());
        String taskId = jdbc.queryForObject("SELECT id FROM background_tasks WHERE kind = 'PUBLISH'", String.class);

        accepted(remove(fixture, "\"v8\""));
        jdbc.update("UPDATE assets SET status = 'READY' WHERE id = ?", cover);
        queue.tick();

        assertDeleted(fixture);
        TaskQueue.Task task = tasks.get(taskId);
        assertEquals("DONE", task.state);
        assertEquals(0, task.attempts);
        assertEquals("", task.payload);
        assertNull(task.leaseToken);
        assertNotNull(task.finishedAt);
        assertError(read(fixture, fixture.owner().access()), 404, "WORK_NOT_FOUND");
    }

    @Test
    void deleteVsSave() throws Exception {
        Fixture fixture = fixture(account(), "DRAFT", "TEXT");
        Map<String, Object> input = Values.map("kind", "TEXT", "title", "并发保存", "body", "新的正文",
                "mediaAssetIds", List.of(), "coverAssetId", null, "musicLinkId", null);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Future<MockHttpServletResponse> removal = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return remove(fixture, "\"v7\"");
            });
            Future<MockHttpServletResponse> save = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return perform(put(PATH + fixture.work().getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.owner().access())
                        .header(HttpHeaders.IF_MATCH, "\"v7\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(input)));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            MockHttpServletResponse removed = removal.get(15, TimeUnit.SECONDS);
            MockHttpServletResponse saved = save.get(15, TimeUnit.SECONDS);
            if (removed.getStatus() == 200) {
                accepted(removed);
                assertError(saved, 404, "WORK_NOT_FOUND");
                assertDeleted(fixture);
            } else {
                assertError(removed, 412, "VERSION_CONFLICT");
                assertEquals(200, saved.getStatus(), saved.getContentAsString());
                assertEquals("\"v8\"", saved.getHeader(HttpHeaders.ETAG));
                WorkRow after = works.get(fixture.work().getId());
                assertEquals(8L, after.getVersion());
                assertEquals("并发保存", after.getTitle());
            }
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
                VALUES (?, ?, ?, 'private-password-hash', '作品作者', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "delete_" + user.replace("-", "").substring(0, 12), CREATED);
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

    private Fixture fixture(Account owner, String state, String kind) {
        var work = new WorkRow();
        work.setId(Values.id());
        work.setOwnerId(owner.user());
        work.setKind(kind);
        work.setStatus(state);
        work.setVersion(7);
        work.setTitle("待删除作品");
        work.setBody("已保存的正文");
        work.setCreatedAt(CREATED);
        work.setUpdatedAt(CREATED + 1000);
        if ("PUBLISHED".equals(state)) work.setPublishedAt(CREATED + 1000);
        if ("FAILED".equals(state)) work.setFailureCode("MEDIA_PROCESSING_FAILED");
        if (Set.of("PROCESSING", "FAILED").contains(state)) work.setPublishTaskId(Values.id());
        works.insert(work);
        return new Fixture(owner, work);
    }

    private List<String> media(Fixture fixture) {
        List<String> ids = List.of(asset(fixture.owner(), "WORK_MEDIA", "READY"),
                asset(fixture.owner(), "WORK_MEDIA", "READY"));
        for (int i = 0; i < ids.size(); i++) works.bind(fixture.work().getId(), ids.get(i), i);
        return ids;
    }

    private String cover(Fixture fixture, String state) {
        String id = asset(fixture.owner(), "WORK_COVER", state);
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", id, fixture.work().getId());
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

    private void relations(Fixture fixture) {
        for (Account actor : List.of(fixture.owner(), account())) {
            jdbc.update("INSERT INTO work_likes (user_id, work_id, created_at) VALUES (?, ?, ?)",
                    actor.user(), fixture.work().getId(), CREATED);
            jdbc.update("INSERT INTO bookmarks (id, user_id, work_id, created_at) VALUES (?, ?, ?, ?)",
                    Values.id(), actor.user(), fixture.work().getId(), CREATED);
            jdbc.update("INSERT INTO comments (id, user_id, work_id, body, created_at) VALUES (?, ?, ?, '评论', ?)",
                    Values.id(), actor.user(), fixture.work().getId(), CREATED);
        }
    }

    private List<Map<String, Object>> assetRows(Account owner) {
        return jdbc.queryForList("SELECT * FROM assets WHERE owner_id = ? ORDER BY id", owner.user());
    }

    private List<Map<String, Object>> rows(String table, String work) {
        // table 仅取测试中写死的表名，work 继续使用参数绑定。
        assertTrue(Set.of("comments", "bookmarks", "work_likes", "work_assets").contains(table));
        String order = "work_likes".equals(table) ? "user_id" : "work_assets".equals(table) ? "position" : "id";
        return jdbc.queryForList("SELECT * FROM " + table + " WHERE work_id = ? ORDER BY " + order, work);
    }

    private Snapshot snapshot(Fixture fixture) {
        String id = fixture.work().getId();
        return new Snapshot(works.get(id), rows("comments", id), rows("bookmarks", id),
                rows("work_likes", id), rows("work_assets", id));
    }

    private void assertRelationsGone(String id) {
        for (String table : List.of("comments", "bookmarks", "work_likes", "work_assets")) {
            assertTrue(rows(table, id).isEmpty(), table + " should be empty for the deleted work");
        }
    }

    private void assertDeleted(Fixture fixture) {
        assertNull(works.get(fixture.work().getId()));
        assertRelationsGone(fixture.work().getId());
    }

    private MockHttpServletResponse remove(Fixture fixture, String match) throws Exception {
        return perform(request(fixture.owner().access(), fixture.work().getId(), match));
    }

    private MockHttpServletRequestBuilder request(String token, String id, String match) {
        var request = delete(PATH + id);
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
        return mvc.perform(request.header("X-Request-Id", "work-delete-" + Values.id()))
                .andReturn().getResponse();
    }

    private void accepted(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(Values.map("success", true), body.get("data"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-delete-"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
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

    private record Account(String user, SessionRow session, String access) {}
    private record Fixture(Account owner, WorkRow work) {}
    private record Snapshot(WorkRow work, List<Map<String, Object>> comments, List<Map<String, Object>> bookmarks,
                            List<Map<String, Object>> likes, List<Map<String, Object>> media) {}
}
