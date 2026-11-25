package com.silver.backend;

import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.SocialMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class LikeTest extends BackendTestSupport {
    private static final String WORKS = "/api/v1/works/";
    private static final long CREATED = 1_700_000_000_000L;

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private SocialMapper social;

    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String user : userIds) {
            for (String table : List.of("work_likes", "bookmarks", "comments", "work_assets")) {
                jdbc.update("DELETE FROM " + table + " WHERE work_id IN (SELECT id FROM works WHERE owner_id = ?)", user);
            }
            jdbc.update("DELETE FROM works WHERE owner_id = ?", user);
        }
        for (String user : userIds) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "other"})
    void likes(String role) throws Exception {
        Account owner = account();
        Account actor = "owner".equals(role) ? owner : account();
        WorkRow row = work(owner, "PUBLISHED");
        SessionRow beforeSession = users.session(actor.session().getId());
        long before = Values.now();

        state(change(row, actor, true), row, true, false, 1, 0);

        assertEquals(1L, social.liked(row.getId(), actor.user()));
        long at = jdbc.queryForObject("SELECT created_at FROM work_likes WHERE work_id = ? AND user_id = ?",
                Long.class, row.getId(), actor.user());
        assertTrue(at >= before && at <= Values.now());
        assertEquals(row, works.get(row.getId()), "点赞不应改变作品内容或版本");
        assertEquals(beforeSession, users.session(beforeSession.getId()));
        Map<String, Object> detail = detail(row, actor);
        assertEquals(Boolean.TRUE, object(detail.get("viewer")).get("liked"));
        assertEquals(1L, ((Number) object(detail.get("stats")).get("likeCount")).longValue());
    }

    @Test
    void repeatsLike() throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        state(change(row, actor, true), row, true, false, 1, 0);
        List<Map<String, Object>> before = likes(row);

        state(change(row, actor, true), row, true, false, 1, 0);
        state(change(row, actor, true), row, true, false, 1, 0);

        assertEquals(before, likes(row), "重复点赞不应新增记录或重写点赞时间");
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "other"})
    void unlikes(String role) throws Exception {
        Account owner = account();
        Account actor = "owner".equals(role) ? owner : account();
        Account another = account();
        WorkRow row = work(owner, "PUBLISHED");
        WorkRow otherWork = work(owner, "PUBLISHED");
        seedLike(row, actor);
        seedLike(row, another);
        seedLike(otherWork, actor);
        bookmark(row, actor);
        bookmark(row, another);
        List<Map<String, Object>> bookmarks = bookmarks(row);

        state(change(row, actor, false), row, false, true, 1, 2);

        assertEquals(0L, social.liked(row.getId(), actor.user()));
        assertEquals(1L, social.liked(row.getId(), another.user()));
        assertEquals(1L, social.liked(otherWork.getId(), actor.user()));
        assertEquals(bookmarks, bookmarks(row));
        assertEquals(row, works.get(row.getId()));
        assertEquals(otherWork, works.get(otherWork.getId()));
        Map<String, Object> detail = detail(row, actor);
        assertEquals(Map.of("liked", false, "bookmarked", true, "isOwner", "owner".equals(role)), detail.get("viewer"));
        assertEquals(1L, ((Number) object(detail.get("stats")).get("likeCount")).longValue());
        assertEquals(2L, ((Number) object(detail.get("stats")).get("bookmarkCount")).longValue());
    }

    @Test
    void repeatsUnlike() throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");

        state(change(row, actor, false), row, false, false, 0, 0);
        state(change(row, actor, false), row, false, false, 0, 0);

        assertEquals(List.of(), likes(row));
    }

    @Test
    void toggles() throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        seedLike(row, actor);
        List<Map<String, Object>> before = likes(row);

        state(change(row, actor, true), row, true, false, 1, 0);
        assertEquals(before, likes(row));
        state(change(row, actor, false), row, false, false, 0, 0);
        long start = Values.now();
        state(change(row, actor, true), row, true, false, 1, 0);

        assertTrue(jdbc.queryForObject("SELECT created_at FROM work_likes WHERE work_id = ? AND user_id = ?",
                Long.class, row.getId(), actor.user()) >= start);
        assertEquals(row, works.get(row.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void needsLogin(boolean enabled) throws Exception {
        WorkRow row = work(account(), "PUBLISHED");

        assertError(perform(request(row.getId(), null, enabled)), 401, "AUTH_REQUIRED");

        assertEquals(List.of(), likes(row));
    }

    @Test
    void cookiesOnly() throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        var request = request(row.getId(), null, true).cookie(
                new Cookie("htw_refresh", actor.refresh()),
                new Cookie("htw_media", crypto.ticket("media-read",
                        Values.map("user", actor.user(), "session", actor.session().getId()), 60_000)));

        assertError(perform(request), 401, "AUTH_REQUIRED");

        assertEquals(List.of(), likes(row));
    }

    @ParameterizedTest
    @CsvSource({"true,invalid", "false,invalid", "true,revoked", "false,revoked"})
    void badBearer(boolean enabled, String kind) throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        if (!enabled) seedLike(row, actor);
        List<Map<String, Object>> before = likes(row);
        String token = "invalid-token";
        String code = "TOKEN_INVALID";
        if ("revoked".equals(kind)) {
            token = actor.access();
            code = "SESSION_REVOKED";
            jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", actor.session().getId());
        }

        assertError(perform(request(row.getId(), token, enabled)), 401, code);

        assertEquals(before, likes(row));
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT,owner,true", "DRAFT,owner,false", "DRAFT,other,true", "DRAFT,other,false",
            "PROCESSING,owner,true", "PROCESSING,owner,false", "PROCESSING,other,true", "PROCESSING,other,false",
            "FAILED,owner,true", "FAILED,owner,false", "FAILED,other,true", "FAILED,other,false"
    })
    void privateWork(String status, String role, boolean enabled) throws Exception {
        Account owner = account();
        Account actor = "owner".equals(role) ? owner : account();
        WorkRow row = work(owner, status);

        assertError(change(row, actor, enabled), "owner".equals(role) ? 409 : 404,
                "owner".equals(role) ? "WORK_NOT_PUBLISHED" : "WORK_NOT_FOUND");

        assertEquals(List.of(), likes(row));
        assertEquals(row, works.get(row.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingWork(boolean enabled) throws Exception {
        assertError(perform(request(Values.id(), account().access(), enabled)), 404, "WORK_NOT_FOUND");
    }

    @Test
    void deletedWork() throws Exception {
        Account owner = account();
        Account actor = account();
        WorkRow row = work(owner, "PUBLISHED");
        state(change(row, actor, true), row, true, false, 1, 0);
        MockHttpServletResponse removal = remove(row, owner);
        assertEquals(200, removal.getStatus(), removal.getContentAsString());

        assertError(change(row, actor, true), 404, "WORK_NOT_FOUND");
        assertError(change(row, actor, false), 404, "WORK_NOT_FOUND");

        assertNull(works.get(row.getId()));
        assertEquals(List.of(), likes(row));
    }

    @Test
    void methodSetsState() throws Exception {
        Account owner = account();
        Account actor = account();
        WorkRow row = work(owner, "PUBLISHED");

        state(perform(request(row.getId(), actor.access(), true)
                .param("userId", owner.user()).param("enabled", "false")), row, true, false, 1, 0);
        assertEquals(1L, social.liked(row.getId(), actor.user()));
        assertEquals(0L, social.liked(row.getId(), owner.user()));
        state(perform(request(row.getId(), actor.access(), false)
                .param("enabled", "true")), row, false, false, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rollsBack(boolean enabled) throws Exception {
        Account actor = account();
        Account another = account();
        WorkRow row = work(actor, "PUBLISHED");
        seedLike(row, another);
        if (!enabled) seedLike(row, actor);
        bookmark(row, actor);
        List<Map<String, Object>> beforeLikes = likes(row);
        List<Map<String, Object>> beforeBookmarks = bookmarks(row);
        SocialMapper actual = sql.getMapper(SocialMapper.class);
        doAnswer(invocation -> {
            // 在真实 INSERT/DELETE 后的响应计数阶段注入异常，验证之前的写入回滚。
            assertEquals(enabled ? 1L : 0L, actual.liked(row.getId(), actor.user()));
            assertEquals(enabled ? 2L : 1L, actual.likes(row.getId()));
            assertEquals(1L, actual.bookmarks(row.getId()));
            throw new IllegalStateException("Failure after updating like relation");
        }).when(social).bookmarks(row.getId());

        assertError(change(row, actor, enabled), 500, "INTERNAL_ERROR");

        assertEquals(beforeLikes, likes(row));
        assertEquals(beforeBookmarks, bookmarks(row));
        assertEquals(row, works.get(row.getId()));
        doAnswer(invocation -> actual.bookmarks(invocation.getArgument(0)))
                .when(social).bookmarks(row.getId());
        state(change(row, actor, enabled), row, enabled, true, enabled ? 2 : 1, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sameUserRace(boolean enabled) throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        if (!enabled) seedLike(row, actor);
        var pool = Executors.newFixedThreadPool(3);
        var ready = new CountDownLatch(3);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return change(row, actor, enabled);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<MockHttpServletResponse> future : futures) {
                state(future.get(15, TimeUnit.SECONDS), row, enabled, false, enabled ? 1 : 0, 0);
            }
            assertEquals(enabled ? 1L : 0L, social.likes(row.getId()));
            assertEquals(row, works.get(row.getId()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentUsers() throws Exception {
        WorkRow row = work(account(), "PUBLISHED");
        List<Account> actors = List.of(account(), account(), account());
        var pool = Executors.newFixedThreadPool(3);
        var ready = new CountDownLatch(3);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (Account actor : actors) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return change(row, actor, true);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var counts = new ArrayList<Long>();
            for (Future<MockHttpServletResponse> future : futures) {
                Map<String, Object> data = data(future.get(15, TimeUnit.SECONDS));
                assertEquals(row.getId(), data.get("workId"));
                assertEquals(Boolean.TRUE, data.get("liked"));
                assertEquals(Boolean.FALSE, data.get("bookmarked"));
                assertEquals(0L, ((Number) data.get("bookmarkCount")).longValue());
                counts.add(((Number) data.get("likeCount")).longValue());
            }
            assertEquals(List.of(1L, 2L, 3L), counts.stream().sorted().toList());
            assertEquals(3L, social.likes(row.getId()));
            for (Account actor : actors) assertEquals(1L, social.liked(row.getId(), actor.user()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mixedRace(boolean initiallyLiked) throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        if (initiallyLiked) seedLike(row, actor);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (boolean enabled : List.of(true, false)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return change(row, actor, enabled);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            state(futures.get(0).get(15, TimeUnit.SECONDS), row, true, false, 1, 0);
            state(futures.get(1).get(15, TimeUnit.SECONDS), row, false, false, 0, 0);
            long count = social.likes(row.getId());
            assertTrue(count == 0 || count == 1);
            assertEquals(count, social.liked(row.getId(), actor.user()));
            Map<String, Object> detail = detail(row, actor);
            assertEquals(count > 0, object(detail.get("viewer")).get("liked"));
            assertEquals(count, ((Number) object(detail.get("stats")).get("likeCount")).longValue());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void deleteRace() throws Exception {
        Account owner = account();
        Account actor = account();
        WorkRow row = work(owner, "PUBLISHED");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var like = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return change(row, actor, true);
            });
            var deletion = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return remove(row, owner);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            MockHttpServletResponse liked = like.get(15, TimeUnit.SECONDS);
            MockHttpServletResponse deleted = deletion.get(15, TimeUnit.SECONDS);
            assertEquals(200, deleted.getStatus(), deleted.getContentAsString());
            if (liked.getStatus() == 200) state(liked, row, true, false, 1, 0);
            else assertError(liked, 404, "WORK_NOT_FOUND");
            assertNull(works.get(row.getId()));
            assertEquals(List.of(), likes(row));
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
                VALUES (?, ?, ?, 'private-password-hash', '点赞用户', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "like_" + user.replace("-", "").substring(0, 12), CREATED);
        userIds.add(user);
        String refresh = crypto.randomToken();
        var session = new SessionRow();
        session.setId(Values.id());
        session.setUserId(user);
        session.setRefreshHash(crypto.digest(refresh));
        session.setCreatedAt(Values.now());
        session.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(session);
        return new Account(user, session, crypto.jwt(user, session.getId(), 900), refresh);
    }

    private WorkRow work(Account owner, String status) {
        var row = new WorkRow();
        row.setId(Values.id());
        row.setOwnerId(owner.user());
        row.setKind("TEXT");
        row.setStatus(status);
        row.setVersion(7);
        row.setTitle("点赞作品");
        row.setBody("正文");
        row.setCreatedAt(CREATED);
        row.setUpdatedAt(CREATED + 1000);
        if ("PUBLISHED".equals(status)) row.setPublishedAt(CREATED + 1000);
        if ("FAILED".equals(status)) row.setFailureCode("MEDIA_PROCESSING_FAILED");
        works.insert(row);
        return row;
    }

    private void seedLike(WorkRow row, Account actor) {
        jdbc.update("INSERT INTO work_likes (user_id, work_id, created_at) VALUES (?, ?, ?)",
                actor.user(), row.getId(), CREATED);
    }

    private void bookmark(WorkRow row, Account actor) {
        jdbc.update("INSERT INTO bookmarks (id, user_id, work_id, created_at) VALUES (?, ?, ?, ?)",
                Values.id(), actor.user(), row.getId(), CREATED);
    }

    private List<Map<String, Object>> likes(WorkRow row) {
        return jdbc.queryForList("SELECT * FROM work_likes WHERE work_id = ? ORDER BY user_id", row.getId());
    }

    private List<Map<String, Object>> bookmarks(WorkRow row) {
        return jdbc.queryForList("SELECT * FROM bookmarks WHERE work_id = ? ORDER BY id", row.getId());
    }

    private MockHttpServletResponse change(WorkRow row, Account actor, boolean enabled) throws Exception {
        return perform(request(row.getId(), actor.access(), enabled));
    }

    private MockHttpServletRequestBuilder request(String work, String token, boolean enabled) {
        String path = WORKS + work + "/like";
        MockHttpServletRequestBuilder request = enabled ? put(path) : delete(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private MockHttpServletResponse remove(WorkRow row, Account owner) throws Exception {
        return perform(delete(WORKS + row.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .header(HttpHeaders.IF_MATCH, "\"v7\""));
    }

    private Map<String, Object> detail(WorkRow row, Account actor) throws Exception {
        MockHttpServletResponse response = perform(get(WORKS + row.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access()));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("\"v7\"", response.getHeader(HttpHeaders.ETAG));
        return object(json.readMap(response.getContentAsString()).get("data"));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "like-" + Values.id())).andReturn().getResponse();
    }

    private void state(MockHttpServletResponse response, WorkRow row, boolean liked, boolean bookmarked,
                       long likeCount, long bookmarkCount) throws Exception {
        Map<String, Object> result = data(response);
        assertEquals(row.getId(), result.get("workId"));
        assertEquals(liked, result.get("liked"));
        assertEquals(bookmarked, result.get("bookmarked"));
        assertEquals(likeCount, ((Number) result.get("likeCount")).longValue());
        assertEquals(bookmarkCount, ((Number) result.get("bookmarkCount")).longValue());
    }

    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("like-"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        Map<String, Object> result = object(body.get("data"));
        assertEquals(Set.of("workId", "liked", "bookmarked", "likeCount", "bookmarkCount"), result.keySet());
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

    private record Account(String user, SessionRow session, String access, String refresh) {}
}
