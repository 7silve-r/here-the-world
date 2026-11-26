package com.silver.backend;

import com.silver.backend.business.persistence.CommentRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.ControlMapper;
import com.silver.backend.business.persistence.mapper.SocialMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class CommentTest extends BackendTestSupport {
    private static final String WORKS = "/api/v1/works/";
    private static final String SOURCE = "192.0.2.21";
    private static final long CREATED = 1_700_000_000_000L;
    private static final Set<String> FIELDS = Set.of("id", "workId", "author", "body", "canDelete", "createdAt");

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private ControlMapper control;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private SocialMapper social;

    private final List<String> userIds = new ArrayList<>();
    private final Set<String> idemIds = ConcurrentHashMap.newKeySet();
    private final Set<String> rateIds = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanFixtures() {
        for (String id : idemIds) jdbc.update("DELETE FROM idempotency WHERE id = ?", id);
        for (String id : rateIds) jdbc.update("DELETE FROM rate_limits WHERE id = ?", id);
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

    @Test
    void creates() throws Exception {
        Account owner = account();
        Account actor = account();
        WorkRow work = work(owner, "PUBLISHED");
        long before = Values.now();

        Map<String, Object> result = data(create(work, actor, Values.id(), "  好作品\n第二行  "), 201);

        CommentRow saved = social.comment((String) result.get("id"));
        assertNotNull(saved);
        assertEquals(work.getId(), saved.getWorkId());
        assertEquals(actor.user(), saved.getUserId());
        assertEquals("好作品\n第二行", saved.getBody());
        assertTrue(saved.getCreatedAt() >= before && saved.getCreatedAt() <= Values.now());
        assertComment(result, saved, actor, true);
        assertEquals(work, works.get(work.getId()), "评论不应修改作品的版本或正文");
        assertCount(work, 1);
    }

    @Test
    void unicodeLimit() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String body = "😀".repeat(2000);

        Map<String, Object> result = data(create(work, owner, Values.id(), body), 201);

        assertEquals(body, result.get("body"));
        assertEquals(body, social.comment((String) result.get("id")).getBody());
        assertCount(work, 1);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBody(Map<String, Object> input, String field, String reason) throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();

        MockHttpServletResponse response = create(work, owner, key, input);

        assertError(response, 422, "VALIDATION_FAILED");
        List<?> details = assertInstanceOf(List.class, json.readMap(response.getContentAsString()).get("details"));
        Map<String, Object> detail = object(details.get(0));
        assertEquals(field, detail.get("field"));
        assertEquals(reason, detail.get("reason"));
        assertEquals(0, social.comments(work.getId()));
        assertNull(control.idem(recordId(work, owner, key)));
        data(create(work, owner, key, "修正后的评论"), 201);
        assertCount(work, 1);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(Map.of(), "body", "REQUIRED"),
                Arguments.of(Values.map("body", null), "body", "REQUIRED"),
                Arguments.of(Map.of("body", 123), "body", "INVALID_FORMAT"),
                Arguments.of(Map.of("body", List.of("评论")), "body", "INVALID_FORMAT"),
                Arguments.of(Map.of("body", ""), "body", "REQUIRED"),
                Arguments.of(Map.of("body", " \n\t\u3000"), "body", "REQUIRED"),
                Arguments.of(Map.of("body", "字".repeat(2001)), "body", "TOO_LONG"),
                Arguments.of(Map.of("body", "😀".repeat(2001)), "body", "TOO_LONG"),
                Arguments.of(Map.of("body", "评论", "userId", "伪造作者"), "userId", "NOT_ALLOWED"));
    }

    @Test
    void malformedJson() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");

        assertError(perform(post(path(work)).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .header("Idempotency-Key", Values.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":")), 400, "BAD_REQUEST");

        assertEquals(0, social.comments(work.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "revoked", "malformed"})
    void needsLogin(String mode) throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow comment = comment(work, owner, CREATED);
        String token = "guest".equals(mode) ? null : "malformed".equals(mode) ? "invalid-token" : owner.access();
        if ("revoked".equals(mode)) jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", owner.session().getId());
        String code = "guest".equals(mode) ? "AUTH_REQUIRED" : "revoked".equals(mode) ? "SESSION_REVOKED" : "TOKEN_INVALID";
        var create = post(path(work)).header("Idempotency-Key", Values.id())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("body", "评论")));
        var remove = delete("/api/v1/comments/" + comment.getId());
        if (token != null) {
            create.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            remove.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        assertError(perform(create), 401, code);
        assertError(perform(remove), 401, code);
        assertEquals(comment, social.comment(comment.getId()));
        assertEquals(1, social.comments(work.getId()));
    }

    @ParameterizedTest
    @CsvSource({"DRAFT,owner", "PROCESSING,owner", "FAILED,owner",
            "DRAFT,other", "PROCESSING,other", "FAILED,other"})
    void unpublished(String status, String role) throws Exception {
        Account owner = account();
        Account author = account();
        Account actor = "owner".equals(role) ? owner : author;
        WorkRow work = work(owner, status);
        CommentRow comment = comment(work, author, CREATED);
        int expected = "owner".equals(role) ? 409 : 404;
        String code = "owner".equals(role) ? "WORK_NOT_PUBLISHED" : "WORK_NOT_FOUND";

        assertError(list(work, actor, null, null), expected, code);
        assertError(create(work, actor, Values.id(), "评论"), expected, code);
        assertError(remove(comment.getId(), actor), expected, code);
        assertError(list(work, null, null, null), 404, "WORK_NOT_FOUND");
        assertEquals(comment, social.comment(comment.getId()));
        assertEquals(1, social.comments(work.getId()));
    }

    @Test
    void missingWork() throws Exception {
        Account owner = account();
        WorkRow missing = work(owner, "PUBLISHED");
        jdbc.update("DELETE FROM works WHERE id = ?", missing.getId());

        assertError(list(missing, null, null, null), 404, "WORK_NOT_FOUND");
        assertError(create(missing, owner, Values.id(), "评论"), 404, "WORK_NOT_FOUND");
        assertEquals(0, social.comments(missing.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "owner", "author", "other"})
    void deleteFlags(String role) throws Exception {
        Account owner = account();
        Account author = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow comment = comment(work, author, CREATED);
        Account actor = switch (role) {
            case "guest" -> null;
            case "owner" -> owner;
            case "author" -> author;
            default -> account();
        };

        Page result = page(list(work, actor, null, null));

        assertEquals(1, result.items().size());
        assertComment(result.items().get(0), comment, author, Set.of("owner", "author").contains(role));
        assertFalse(result.more());
        assertNull(result.cursor());
    }

    @Test
    void emptyPage() throws Exception {
        WorkRow work = work(account(), "PUBLISHED");

        Page result = page(list(work, null, null, null));

        assertEquals(List.of(), result.items());
        assertNull(result.cursor());
        assertFalse(result.more());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24, 50})
    void pageLimits(int limit) throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        List<CommentRow> rows = comments(work, owner, limit + 1);
        WorkRow unrelated = work(owner, "PUBLISHED");
        comment(unrelated, owner, CREATED);

        Page first = page(list(work, null, limit == 24 ? null : limit, null));

        assertEquals(ids(rows.subList(0, limit)), ids(first));
        assertTrue(first.more());
        assertCursor(first, work, rows.get(limit - 1));
        Page last = page(list(work, owner, limit, first.cursor()));
        assertEquals(List.of(rows.get(limit).getId()), ids(last));
        assertFalse(last.more());
    }

    @Test
    void sameTimePages() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        List<CommentRow> rows = comments(work, owner, 8);
        var received = new ArrayList<String>();
        String cursor = null;
        for (int i = 0; i < 4; i++) {
            Page result = page(list(work, null, 2, cursor));
            received.addAll(ids(result));
            if (result.more()) assertCursor(result, work, rows.get(i * 2 + 1));
            cursor = result.cursor();
            assertEquals(i < 3, result.more());
        }

        assertEquals(ids(rows), received);
        assertEquals(8, Set.copyOf(received).size());
        assertNull(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "2147483648", "abc"})
    void invalidLimit(String limit) throws Exception {
        WorkRow work = work(account(), "PUBLISHED");

        assertError(perform(get(path(work)).param("limit", limit)), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-work", "other-route", "filter", "expired", "tampered", "oversized"})
    void invalidCursor(String kind) throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String route = route(work);
        if ("other-work".equals(kind)) route = route(work(owner, "PUBLISHED"));
        if ("other-route".equals(kind)) route = "/me/comments";
        String cursor = crypto.ticket("cursor", Values.map("route", route,
                "filter", "filter".equals(kind) ? owner.user() : "", "time", CREATED, "id", "boundary"),
                "expired".equals(kind) ? -1000 : 60_000);
        if ("tampered".equals(kind)) cursor = "invalid." + cursor.split("\\.")[1];
        if ("oversized".equals(kind)) cursor = "x".repeat(4097);

        assertError(list(work, null, 2, cursor), 400, "INVALID_CURSOR");
    }

    @Test
    void pageAfterDelete() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        List<CommentRow> rows = comments(work, owner, 5);
        Page first = page(list(work, null, 2, null));
        data(remove(rows.get(1).getId(), owner), 200);
        data(remove(rows.get(2).getId(), owner), 200);

        Page last = page(list(work, null, 2, first.cursor()));

        assertEquals(ids(rows.subList(3, 5)), ids(last));
        assertFalse(last.more());
        assertCount(work, 3);
    }

    @Test
    void newComment() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        List<CommentRow> rows = comments(work, owner, 3);
        Page first = page(list(work, null, 2, null));
        String added = (String) data(create(work, owner, Values.id(), "新评论"), 201).get("id");

        Page last = page(list(work, null, 2, first.cursor()));

        assertEquals(List.of(rows.get(2).getId(), added), ids(last));
        assertFalse(last.more());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "", "not-a-uuid", "1-1-1-1-1"})
    void invalidKey(String key) throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");

        assertError(create(work, owner, "missing".equals(key) ? null : key, "评论"), 400, "BAD_REQUEST");
        assertEquals(0, social.comments(work.getId()));
    }

    @Test
    void sameKey() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> first = data(create(work, owner, key, "评论"), 201);

        Map<String, Object> replay = data(create(work, owner, key, "评论"), 201);

        assertEquals(first, replay);
        assertCount(work, 1);
        String encrypted = control.idem(recordId(work, owner, key)).getResponseJson();
        assertNotNull(encrypted);
        assertFalse(encrypted.contains((String) first.get("id")));
    }

    @Test
    void conflictingBody() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();
        Map<String, Object> first = data(create(work, owner, key, " 评论 "), 201);

        assertError(create(work, owner, key, "评论"), 409, "IDEMPOTENCY_CONFLICT");
        assertError(create(work, owner, key, "其他内容"), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(first, data(create(work, owner, key, " 评论 "), 201));
        assertCount(work, 1);
    }

    @Test
    void keyScopes() throws Exception {
        Account owner = account();
        Account other = account();
        WorkRow a = work(owner, "PUBLISHED");
        WorkRow b = work(owner, "PUBLISHED");
        String key = Values.id();

        String first = (String) data(create(a, owner, key, "相同内容"), 201).get("id");
        String second = (String) data(create(a, other, key, "相同内容"), 201).get("id");
        String third = (String) data(create(b, owner, key, "相同内容"), 201).get("id");
        String fourth = (String) data(create(a, owner, Values.id(), "相同内容"), 201).get("id");

        assertEquals(4, Set.of(first, second, third, fourth).size());
        assertCount(a, 3);
        assertCount(b, 1);
    }

    @Test
    void cachedDeletion() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();
        Map<String, Object> first = data(create(work, owner, key, "评论"), 201);
        data(remove((String) first.get("id"), owner), 200);

        assertEquals(first, data(create(work, owner, key, "评论"), 201));
        assertNull(social.comment((String) first.get("id")));
        assertCount(work, 0);
    }

    @Test
    void activeClaim() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();
        String id = recordId(work, owner, key);
        jdbc.update("""
                INSERT INTO idempotency (id, body_hash, expires_at, lease_until, lease_token)
                VALUES (?, ?, ?, ?, 'previous-owner')
                """, id, crypto.digest(json.write(Map.of("body", "评论"))), Values.now() + 60_000, Values.now() + 30_000);

        MockHttpServletResponse response = create(work, owner, key, "评论");

        assertError(response, 409, "REQUEST_IN_PROGRESS");
        assertEquals("2", response.getHeader("Retry-After"));
        assertError(create(work, owner, key, "不同内容"), 409, "IDEMPOTENCY_CONFLICT");
        assertEquals(0, social.comments(work.getId()));
        jdbc.update("UPDATE idempotency SET lease_until = ? WHERE id = ?", Values.now() - 1000, id);
        data(create(work, owner, key, "评论"), 201);
        assertNotEquals("previous-owner", control.idem(id).getLeaseToken());
        assertCount(work, 1);
    }

    @Test
    void expiredKey() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();
        Map<String, Object> first = data(create(work, owner, key, "评论"), 201);
        jdbc.update("UPDATE idempotency SET expires_at = ? WHERE id = ?", Values.now() - 1000, recordId(work, owner, key));

        Map<String, Object> second = data(create(work, owner, key, "新的意图"), 201);

        assertNotEquals(first.get("id"), second.get("id"));
        assertCount(work, 2);
    }

    @Test
    void createRollback() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();
        SocialMapper actual = sql.getMapper(SocialMapper.class);
        var inserted = new AtomicReference<String>();
        // 先执行真实 INSERT 再制造故障，验证评论和幂等结果属于同一个事务。
        doAnswer(invocation -> {
            CommentRow row = invocation.getArgument(0);
            actual.insertComment(row);
            inserted.set(row.getId());
            assertNotNull(actual.comment(row.getId()));
            throw new IllegalStateException("Failure after real comment insertion");
        }).when(social).insertComment(any(CommentRow.class));

        assertError(create(work, owner, key, "评论"), 500, "INTERNAL_ERROR");

        assertNotNull(inserted.get());
        assertNull(social.comment(inserted.get()));
        assertNull(control.idem(recordId(work, owner, key)));
        assertCount(work, 0);
        doAnswer(invocation -> {
            actual.insertComment(invocation.getArgument(0));
            return null;
        }).when(social).insertComment(any(CommentRow.class));
        data(create(work, owner, key, "评论"), 201);
        assertCount(work, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "author"})
    void deletes(String role) throws Exception {
        Account owner = account();
        Account author = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow target = comment(work, author, CREATED);
        CommentRow retained = comment(work, author, CREATED + 1000);
        Account actor = "owner".equals(role) ? owner : author;

        assertEquals(Map.of("success", true), data(remove(target.getId(), actor), 200));

        assertNull(social.comment(target.getId()));
        assertEquals(retained, social.comment(retained.getId()));
        assertEquals(work, works.get(work.getId()));
        assertCount(work, 1);
        assertEquals(List.of(retained.getId()), ids(page(list(work, null, null, null))));
        assertError(remove(target.getId(), actor), 404, "COMMENT_NOT_FOUND");
    }

    @Test
    void forbidsDelete() throws Exception {
        Account owner = account();
        Account author = account();
        Account stranger = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow row = comment(work, author, CREATED);

        assertError(remove(row.getId(), stranger), 403, "FORBIDDEN");

        assertEquals(row, social.comment(row.getId()));
        assertCount(work, 1);
    }

    @Test
    void missingComment() throws Exception {
        assertError(remove(Values.id(), account()), 404, "COMMENT_NOT_FOUND");
    }

    @Test
    void deleteRollback() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow row = comment(work, owner, CREATED);
        SocialMapper actual = sql.getMapper(SocialMapper.class);
        doAnswer(invocation -> {
            actual.deleteComment(row.getId());
            assertNull(actual.comment(row.getId()));
            throw new IllegalStateException("Failure after real comment deletion");
        }).when(social).deleteComment(row.getId());

        assertError(remove(row.getId(), owner), 500, "INTERNAL_ERROR");

        assertEquals(row, social.comment(row.getId()));
        assertCount(work, 1);
    }

    @Test
    void rechecksHint() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow row = comment(work, owner, CREATED);
        SocialMapper actual = sql.getMapper(SocialMapper.class);
        var reads = new AtomicInteger();
        doAnswer(invocation -> {
            CommentRow found = actual.comment(row.getId());
            if (reads.incrementAndGet() == 1) actual.deleteComment(row.getId());
            return found;
        }).when(social).comment(row.getId());

        assertError(remove(row.getId(), owner), 404, "COMMENT_NOT_FOUND");

        assertEquals(2, reads.get(), "hint 仅用于定位作品，取得锁后必须重新读取评论");
        assertCount(work, 0);
    }

    @Test
    void deletesWithWork() throws Exception {
        Account owner = account();
        Account author = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow row = comment(work, author, CREATED);

        data(removeWork(work, owner), 200);

        assertNull(social.comment(row.getId()));
        assertEquals(0, social.comments(work.getId()));
        assertError(list(work, null, null, null), 404, "WORK_NOT_FOUND");
        assertError(remove(row.getId(), author), 404, "COMMENT_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "different"})
    void concurrentKey(String mode) throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String key = Values.id();
        String secondBody = "same".equals(mode) ? "评论" : "不同评论";

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> create(work, owner, key, "评论"), () -> create(work, owner, key, secondBody)));

        String id = null;
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 201) {
                String current = (String) data(response, 201).get("id");
                if (id != null) assertEquals(id, current);
                id = current;
            } else {
                assertError(response, 409, "same".equals(mode) ? "REQUEST_IN_PROGRESS" : "IDEMPOTENCY_CONFLICT");
            }
        }
        assertNotNull(id);
        if ("different".equals(mode)) assertEquals(1, responses.stream().filter(r -> r.getStatus() == 201).count());
        assertCount(work, 1);
    }

    @Test
    void concurrentCreates() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> create(work, owner, Values.id(), "第一条"),
                () -> create(work, owner, Values.id(), "第二条")));

        assertNotEquals(data(responses.get(0), 201).get("id"), data(responses.get(1), 201).get("id"));
        assertCount(work, 2);
    }

    @Test
    void concurrentDeletes() throws Exception {
        Account owner = account();
        Account author = account();
        WorkRow work = work(owner, "PUBLISHED");
        CommentRow row = comment(work, author, CREATED);

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> remove(row.getId(), owner), () -> remove(row.getId(), author)));

        assertEquals(1, responses.stream().filter(r -> r.getStatus() == 200).count());
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 200) assertEquals(Map.of("success", true), data(response, 200));
            else assertError(response, 404, "COMMENT_NOT_FOUND");
        }
        assertCount(work, 0);
    }

    @Test
    void racesWorkDelete() throws Exception {
        Account owner = account();
        Account author = account();
        WorkRow work = work(owner, "PUBLISHED");

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> create(work, author, Values.id(), "评论"), () -> removeWork(work, owner)));

        if (responses.get(0).getStatus() == 201) data(responses.get(0), 201);
        else assertError(responses.get(0), 404, "WORK_NOT_FOUND");
        data(responses.get(1), 200);
        assertNull(works.get(work.getId()));
        assertEquals(0, social.comments(work.getId()));
    }

    @Test
    void accountLimit() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String rate = seedRate("comment-account", owner.user(), 19, Values.now() + 60_000);
        data(create(work, owner, Values.id(), "第20次请求"), 201);
        String key = Values.id();

        MockHttpServletResponse response = create(work, owner, key, "超限请求");

        assertError(response, 429, "RATE_LIMITED");
        assertEquals("60", response.getHeader("Retry-After"));
        assertEquals(20, control.rate(rate, Values.now()));
        assertNull(control.idem(recordId(work, owner, key)));
        data(create(work, account(), Values.id(), "其他账号"), 201);
        assertCount(work, 2);
    }

    @Test
    void sourceLimit() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String rate = seedRate("comment-source", SOURCE, 59, Values.now() + 60_000);
        data(create(work, owner, Values.id(), "第60次请求"), 201);
        Account other = account();
        String key = Values.id();

        MockHttpServletResponse response = create(work, other, key, "同来源超限");

        assertError(response, 429, "RATE_LIMITED");
        assertEquals("60", response.getHeader("Retry-After"));
        assertEquals(60, control.rate(rate, Values.now()));
        assertNull(control.idem(recordId(work, other, key)));
        assertCount(work, 1);
    }

    @Test
    void rateExpires() throws Exception {
        Account owner = account();
        WorkRow work = work(owner, "PUBLISHED");
        String accountRate = seedRate("comment-account", owner.user(), 20, Values.now() - 1000);
        String sourceRate = seedRate("comment-source", SOURCE, 60, Values.now() - 1000);

        data(create(work, owner, Values.id(), "新窗口"), 201);

        assertEquals(1, control.rate(accountRate, Values.now()));
        assertEquals(1, control.rate(sourceRate, Values.now()));
        assertCount(work, 1);
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '评论用户', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "comment_" + user.replace("-", "").substring(0, 12), CREATED);
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

    private WorkRow work(Account owner, String status) {
        var row = new WorkRow();
        row.setId(Values.id());
        row.setOwnerId(owner.user());
        row.setKind("TEXT");
        row.setStatus(status);
        row.setVersion(7);
        row.setTitle("评论作品");
        row.setBody("作品正文");
        row.setCreatedAt(CREATED);
        row.setUpdatedAt(CREATED + 1000);
        if ("PUBLISHED".equals(status)) row.setPublishedAt(CREATED + 1000);
        if ("FAILED".equals(status)) row.setFailureCode("MEDIA_PROCESSING_FAILED");
        works.insert(row);
        return row;
    }

    private CommentRow comment(WorkRow work, Account author, long at) {
        var row = new CommentRow();
        row.setId(Values.id());
        row.setWorkId(work.getId());
        row.setUserId(author.user());
        row.setBody("评论正文");
        row.setCreatedAt(at);
        social.insertComment(row);
        return row;
    }

    private List<CommentRow> comments(WorkRow work, Account author, int count) {
        var rows = new ArrayList<CommentRow>();
        for (int i = 0; i < count; i++) rows.add(comment(work, author, CREATED + (i / 3) * 1000L));
        rows.sort(Comparator.comparingLong(CommentRow::getCreatedAt).thenComparing(CommentRow::getId));
        return rows;
    }

    private String recordId(WorkRow work, Account actor, String key) {
        String id = crypto.digest(actor.user() + "\nPOST\n" + route(work) + "\n" + key);
        idemIds.add(id);
        return id;
    }

    private String rateId(String scope, String identity) {
        String id = crypto.keyedDigest("limit", scope + ":" + identity);
        rateIds.add(id);
        return id;
    }

    private String seedRate(String scope, String identity, int hits, long expiry) {
        String id = rateId(scope, identity);
        jdbc.update("INSERT INTO rate_limits (id, hits, expires_at) VALUES (?, ?, ?)", id, hits, expiry);
        return id;
    }

    private MockHttpServletResponse create(WorkRow work, Account actor, String key, String body) throws Exception {
        return create(work, actor, key, Map.of("body", body));
    }

    private MockHttpServletResponse create(WorkRow work, Account actor, String key, Map<String, Object> input)
            throws Exception {
        if (key != null) recordId(work, actor, key);
        rateId("comment-account", actor.user());
        rateId("comment-source", SOURCE);
        var request = post(path(work)).header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(input));
        if (key != null) request.header("Idempotency-Key", key);
        return perform(request);
    }

    private MockHttpServletResponse list(WorkRow work, Account actor, Integer limit, String cursor) throws Exception {
        var request = get(path(work));
        if (actor != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access());
        if (limit != null) request.param("limit", limit.toString());
        if (cursor != null) request.param("cursor", cursor);
        return perform(request);
    }

    private MockHttpServletResponse remove(String id, Account actor) throws Exception {
        return perform(delete("/api/v1/comments/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access()));
    }

    private MockHttpServletResponse removeWork(WorkRow work, Account owner) throws Exception {
        return perform(delete(WORKS + work.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .header(HttpHeaders.IF_MATCH, "\"v7\""));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "comment-" + Values.id()).with(r -> {
            r.setRemoteAddr(SOURCE);
            return r;
        })).andReturn().getResponse();
    }

    private String route(WorkRow work) {
        return "/works/" + work.getId() + "/comments";
    }

    private String path(WorkRow work) {
        return "/api/v1" + route(work);
    }

    private void assertCount(WorkRow work, long expected) throws Exception {
        assertEquals(expected, social.comments(work.getId()));
        MockHttpServletResponse response = perform(get(WORKS + work.getId()));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        Map<String, Object> detail = object(json.readMap(response.getContentAsString()).get("data"));
        assertEquals(expected, ((Number) object(detail.get("stats")).get("commentCount")).longValue());
    }

    private void assertComment(Map<String, Object> item, CommentRow row, Account author, boolean canDelete) {
        assertEquals(FIELDS, item.keySet());
        assertEquals(row.getId(), item.get("id"));
        assertEquals(row.getWorkId(), item.get("workId"));
        assertEquals(row.getBody(), item.get("body"));
        assertEquals(Values.time(row.getCreatedAt()), item.get("createdAt"));
        assertEquals(canDelete, item.get("canDelete"));
        Map<String, Object> user = object(item.get("author"));
        assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount", "createdAt"),
                user.keySet());
        assertEquals(author.user(), user.get("id"));
    }

    private Page page(MockHttpServletResponse response) throws Exception {
        Map<String, Object> result = data(response, 200);
        assertEquals(Set.of("items", "nextCursor", "hasMore"), result.keySet());
        List<?> raw = assertInstanceOf(List.class, result.get("items"));
        List<Map<String, Object>> items = raw.stream().map(this::object).toList();
        for (Map<String, Object> item : items) assertEquals(FIELDS, item.keySet());
        boolean more = assertInstanceOf(Boolean.class, result.get("hasMore"));
        String cursor = (String) result.get("nextCursor");
        if (more) assertNotNull(cursor);
        else assertNull(cursor);
        return new Page(items, cursor, more);
    }

    private List<String> ids(Page page) {
        return page.items().stream().map(item -> (String) item.get("id")).toList();
    }

    private List<String> ids(List<CommentRow> rows) {
        return rows.stream().map(CommentRow::getId).toList();
    }

    private void assertCursor(Page page, WorkRow work, CommentRow last) {
        Map<String, Object> claims = crypto.open("cursor", page.cursor(), "INVALID_CURSOR");
        assertEquals(route(work), claims.get("route"));
        assertEquals("", claims.get("filter"));
        assertEquals(last.getId(), claims.get("id"));
        assertEquals(last.getCreatedAt(), ((Number) claims.get("time")).longValue());
    }

    private Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        return object(body.get("data"));
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

    private List<MockHttpServletResponse> parallel(List<Callable<MockHttpServletResponse>> actions) throws Exception {
        var pool = Executors.newFixedThreadPool(actions.size());
        var ready = new CountDownLatch(actions.size());
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (Callable<MockHttpServletResponse> action : actions) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return action.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var responses = new ArrayList<MockHttpServletResponse>();
            for (Future<MockHttpServletResponse> future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
            return responses;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private record Account(String user, SessionRow session, String access) {}
    private record Page(List<Map<String, Object>> items, String cursor, boolean more) {}
}
