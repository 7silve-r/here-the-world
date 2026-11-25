package com.silver.backend;

import com.silver.backend.business.persistence.BookmarkRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.SocialMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class BookmarkTest extends BackendTestSupport {
    private static final String WORKS = "/api/v1/works/";
    private static final String LIST = "/api/v1/me/bookmarks";
    private static final long CREATED = 1_700_000_000_000L;
    private static final Set<String> CARD_FIELDS = Set.of(
            "id", "kind", "status", "version", "title", "excerpt", "author", "cover", "stats",
            "viewer", "createdAt", "publishedAt");

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private WorkService workService;
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
    void adds(String role) throws Exception {
        Account owner = account();
        Account actor = "owner".equals(role) ? owner : account();
        WorkRow row = work(owner, "PUBLISHED");
        seedLike(row, actor);
        SessionRow beforeSession = users.session(actor.session().getId());
        long before = Values.now();

        state(change(row, actor, true), row, true, true, 1, 1);

        assertEquals(1L, social.bookmarked(row.getId(), actor.user()));
        Map<String, Object> saved = bookmarks(row).get(0);
        assertInstanceOf(String.class, saved.get("id"));
        long at = ((Number) saved.get("created_at")).longValue();
        assertTrue(at >= before && at <= Values.now());
        assertEquals(row, works.get(row.getId()), "收藏不应修改作品内容或版本");
        assertEquals(beforeSession, users.session(beforeSession.getId()));
        assertEquals(List.of(row.getId()), ids(page(list(actor, 2, null))));
        Map<String, Object> detail = detail(row, actor);
        assertEquals(Boolean.TRUE, object(detail.get("viewer")).get("bookmarked"));
        assertEquals(1L, ((Number) object(detail.get("stats")).get("bookmarkCount")).longValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "other"})
    void removes(String role) throws Exception {
        Account owner = account();
        Account actor = "owner".equals(role) ? owner : account();
        Account another = account();
        WorkRow row = work(owner, "PUBLISHED");
        WorkRow other = work(owner, "PUBLISHED");
        bookmark(row, actor, CREATED);
        bookmark(row, another, CREATED);
        bookmark(other, actor, CREATED);
        seedLike(row, actor);
        seedLike(row, another);
        List<Map<String, Object>> beforeLikes = likes(row);

        state(change(row, actor, false), row, true, false, 2, 1);

        assertEquals(0L, social.bookmarked(row.getId(), actor.user()));
        assertEquals(1L, social.bookmarked(row.getId(), another.user()));
        assertEquals(1L, social.bookmarked(other.getId(), actor.user()));
        assertEquals(beforeLikes, likes(row));
        assertEquals(List.of(other.getId()), ids(page(list(actor, 10, null))));
        assertEquals(row, works.get(row.getId()));
        assertEquals(other, works.get(other.getId()));
    }

    @Test
    void repeatsAndCycles() throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        state(change(row, actor, true), row, false, true, 0, 1);
        List<Map<String, Object>> before = bookmarks(row);

        state(change(row, actor, true), row, false, true, 0, 1);
        assertEquals(before, bookmarks(row), "重复收藏应保留原 ID 和收藏时间");
        state(change(row, actor, false), row, false, false, 0, 0);
        state(change(row, actor, false), row, false, false, 0, 0);
        long start = Values.now();
        state(change(row, actor, true), row, false, true, 0, 1);

        Map<String, Object> saved = bookmarks(row).get(0);
        assertNotEquals(before.get(0).get("id"), saved.get("id"));
        assertTrue(((Number) saved.get("created_at")).longValue() >= start);
        assertEquals(row, works.get(row.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "remove", "list"})
    void needsLogin(String operation) throws Exception {
        WorkRow row = work(account(), "PUBLISHED");
        var request = "list".equals(operation) ? get(LIST) : request(row.getId(), null, "add".equals(operation));

        assertError(perform(request), 401, "AUTH_REQUIRED");

        assertEquals(List.of(), bookmarks(row));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "revoked"})
    void badBearer(String kind) throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        bookmark(row, actor, CREATED);
        List<Map<String, Object>> before = bookmarks(row);
        String token = "invalid-token";
        String code = "TOKEN_INVALID";
        if ("revoked".equals(kind)) {
            token = actor.access();
            code = "SESSION_REVOKED";
            jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", actor.session().getId());
        }

        assertError(perform(request(row.getId(), token, true)), 401, code);
        assertError(perform(request(row.getId(), token, false)), 401, code);
        assertError(perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 401, code);

        assertEquals(before, bookmarks(row));
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

        assertEquals(List.of(), bookmarks(row));
        assertEquals(row, works.get(row.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingWork(boolean enabled) throws Exception {
        assertError(perform(request(Values.id(), account().access(), enabled)), 404, "WORK_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rollsBack(boolean enabled) throws Exception {
        Account actor = account();
        Account another = account();
        WorkRow row = work(actor, "PUBLISHED");
        bookmark(row, another, CREATED);
        if (!enabled) bookmark(row, actor, CREATED);
        seedLike(row, actor);
        List<Map<String, Object>> beforeBookmarks = bookmarks(row);
        List<Map<String, Object>> beforeLikes = likes(row);
        SocialMapper actual = sql.getMapper(SocialMapper.class);
        doAnswer(invocation -> {
            // 在真实 INSERT/DELETE 后的响应计数阶段注入故障，验证关系写入整体回滚。
            assertEquals(enabled ? 1L : 0L, actual.bookmarked(row.getId(), actor.user()));
            assertEquals(enabled ? 2L : 1L, actual.bookmarks(row.getId()));
            throw new IllegalStateException("Failure after updating bookmark relation");
        }).when(social).bookmarks(row.getId());

        assertError(change(row, actor, enabled), 500, "INTERNAL_ERROR");

        assertEquals(beforeBookmarks, bookmarks(row));
        assertEquals(beforeLikes, likes(row));
        assertEquals(row, works.get(row.getId()));
        doAnswer(invocation -> actual.bookmarks(invocation.getArgument(0)))
                .when(social).bookmarks(row.getId());
        state(change(row, actor, enabled), row, true, enabled, 1, enabled ? 2 : 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sameUserRace(boolean enabled) throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        if (!enabled) bookmark(row, actor, CREATED);

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> change(row, actor, enabled),
                () -> change(row, actor, enabled),
                () -> change(row, actor, enabled)));

        for (MockHttpServletResponse response : responses) state(response, row, false, enabled, 0, enabled ? 1 : 0);
        assertEquals(enabled ? 1L : 0L, social.bookmarks(row.getId()));
        assertEquals(row, works.get(row.getId()));
    }

    @Test
    void differentUsers() throws Exception {
        WorkRow row = work(account(), "PUBLISHED");
        List<Account> actors = List.of(account(), account(), account());
        var actions = new ArrayList<Callable<MockHttpServletResponse>>();
        for (Account actor : actors) actions.add(() -> change(row, actor, true));

        List<MockHttpServletResponse> responses = parallel(actions);

        var counts = new ArrayList<Long>();
        for (MockHttpServletResponse response : responses) {
            Map<String, Object> result = data(response);
            assertEquals(row.getId(), result.get("workId"));
            assertEquals(Boolean.TRUE, result.get("bookmarked"));
            assertEquals(Boolean.FALSE, result.get("liked"));
            assertEquals(0L, ((Number) result.get("likeCount")).longValue());
            counts.add(((Number) result.get("bookmarkCount")).longValue());
        }
        assertEquals(List.of(1L, 2L, 3L), counts.stream().sorted().toList());
        assertEquals(3L, social.bookmarks(row.getId()));
        for (Account actor : actors) assertEquals(1L, social.bookmarked(row.getId(), actor.user()));
    }

    @Test
    void mixedRace() throws Exception {
        Account actor = account();
        WorkRow row = work(actor, "PUBLISHED");
        bookmark(row, actor, CREATED);
        seedLike(row, actor);

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> change(row, actor, true), () -> change(row, actor, false)));

        state(responses.get(0), row, true, true, 1, 1);
        state(responses.get(1), row, true, false, 1, 0);
        long count = social.bookmarks(row.getId());
        assertTrue(count == 0 || count == 1);
        assertEquals(count, social.bookmarked(row.getId(), actor.user()));
        assertEquals(1L, social.likes(row.getId()));
        Map<String, Object> detail = detail(row, actor);
        assertEquals(count > 0, object(detail.get("viewer")).get("bookmarked"));
        assertEquals(count, ((Number) object(detail.get("stats")).get("bookmarkCount")).longValue());
    }

    @Test
    void deleteRace() throws Exception {
        Account owner = account();
        Account actor = account();
        WorkRow row = work(owner, "PUBLISHED");

        List<MockHttpServletResponse> responses = parallel(List.of(
                () -> change(row, actor, true), () -> remove(row.getId(), owner)));

        MockHttpServletResponse saved = responses.get(0);
        MockHttpServletResponse deleted = responses.get(1);
        assertEquals(200, deleted.getStatus(), deleted.getContentAsString());
        if (saved.getStatus() == 200) state(saved, row, false, true, 0, 1);
        else assertError(saved, 404, "WORK_NOT_FOUND");
        assertNull(works.get(row.getId()));
        assertEquals(List.of(), bookmarks(row));
        assertEquals(List.of(), page(list(actor, 2, null)).items());
        assertError(change(row, actor, true), 404, "WORK_NOT_FOUND");
        assertError(change(row, actor, false), 404, "WORK_NOT_FOUND");
    }

    @Test
    void ownScope() throws Exception {
        Account actor = account();
        Account author = account();
        Account other = account();
        WorkRow first = work(author, "PUBLISHED");
        WorkRow own = work(actor, "PUBLISHED");
        WorkRow hidden = work(author, "DRAFT");
        WorkRow uncollected = work(author, "PUBLISHED");
        bookmark(first, actor, CREATED);
        bookmark(own, actor, CREATED + 1000);
        bookmark(hidden, actor, CREATED + 2000);
        bookmark(uncollected, other, CREATED + 3000);
        seedLike(own, actor);
        jdbc.update("UPDATE works SET body = ? WHERE id = ?", "😀".repeat(281), own.getId());
        SessionRow before = users.session(actor.session().getId());

        Page result = page(perform(get(LIST).param("userId", other.user())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access())));

        assertEquals(List.of(own.getId(), first.getId()), ids(result));
        assertFalse(result.more());
        Map<String, Object> card = result.items().get(0);
        assertEquals("😀".repeat(280), card.get("excerpt"));
        assertEquals(Map.of("liked", true, "bookmarked", true, "isOwner", true), card.get("viewer"));
        assertEquals(1L, ((Number) object(card.get("stats")).get("bookmarkCount")).longValue());
        assertEquals(Boolean.FALSE, object(result.items().get(1).get("viewer")).get("isOwner"));
        assertEquals(1L, social.bookmarked(hidden.getId(), actor.user()), "列表过滤不应删除收藏关系");
        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void emptyList() throws Exception {
        Page result = page(list(account(), null, null));

        assertEquals(List.of(), result.items());
        assertFalse(result.more());
        assertNull(result.cursor());
    }

    @Test
    void defaultLimit() throws Exception {
        Listing fixture = listing(26);

        Page result = page(list(fixture.actor(), null, null));

        assertEquals(workIds(fixture.rows().subList(0, 24)), ids(result));
        assertTrue(result.more());
        assertCursor(result, fixture.actor(), fixture.rows().get(23));
    }

    @Test
    void maximumLimit() throws Exception {
        Listing fixture = listing(51);

        Page result = page(list(fixture.actor(), 50, null));

        assertEquals(workIds(fixture.rows().subList(0, 50)), ids(result));
        assertTrue(result.more());
        assertCursor(result, fixture.actor(), fixture.rows().get(49));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void badLimit(int limit) throws Exception {
        assertError(list(account(), limit, null), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5"})
    void badLimitType(String value) throws Exception {
        assertError(perform(get(LIST).param("limit", value)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account().access())), 400, "BAD_REQUEST");
    }

    @Test
    void walksPages() throws Exception {
        Listing fixture = listing(9);
        var seen = new HashSet<String>();
        var collected = new ArrayList<String>();
        String cursor = null;
        for (int offset = 0; offset < fixture.rows().size(); offset += 2) {
            Page result = page(list(fixture.actor(), 2, cursor));
            int end = Math.min(offset + 2, fixture.rows().size());
            assertEquals(workIds(fixture.rows().subList(offset, end)), ids(result));
            for (String id : ids(result)) assertTrue(seen.add(id), "同时间戳翻页不能重复返回作品");
            collected.addAll(ids(result));
            assertEquals(end < fixture.rows().size(), result.more());
            if (result.more()) {
                assertCursor(result, fixture.actor(), fixture.rows().get(end - 1));
                assertNotEquals(cursor, result.cursor());
            }
            cursor = result.cursor();
        }
        assertEquals(workIds(fixture.rows()), collected);
        assertNull(cursor);
    }

    @Test
    void exactLastPage() throws Exception {
        Listing fixture = listing(4);
        Page first = page(list(fixture.actor(), 2, null));

        Page last = page(list(fixture.actor(), 2, first.cursor()));

        assertEquals(workIds(fixture.rows().subList(2, 4)), ids(last));
        assertFalse(last.more());
        assertNull(last.cursor());
    }

    @Test
    void anotherUserCursor() throws Exception {
        Listing first = listing(3);
        Account second = account();
        Page source = page(list(first.actor(), 1, null));

        assertError(list(second, 1, source.cursor()), 400, "INVALID_CURSOR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/me/works", "/feed"})
    void wrongRoute(String route) throws Exception {
        Account actor = account();
        String cursor = crypto.ticket("cursor", Values.map("route", route, "filter", actor.user(),
                "time", CREATED, "id", Values.id()), 60_000);

        assertError(list(actor, 2, cursor), 400, "INVALID_CURSOR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-cursor"})
    void badCursor(String cursor) throws Exception {
        assertError(list(account(), 2, cursor), 400, "INVALID_CURSOR");
    }

    @Test
    void expiredCursor() throws Exception {
        Account actor = account();
        String cursor = crypto.ticket("cursor", Values.map("route", "/me/bookmarks", "filter", actor.user(),
                "time", CREATED, "id", Values.id()), -60_000);

        assertError(list(actor, 2, cursor), 400, "INVALID_CURSOR");
    }

    @Test
    void tamperedCursor() throws Exception {
        Listing fixture = listing(3);
        Page first = page(list(fixture.actor(), 1, null));
        String[] parts = first.cursor().split("\\.", -1);
        Map<String, Object> claims = json.readMap(new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        claims.put("id", "modified-bookmark-id");
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.write(claims).getBytes(StandardCharsets.UTF_8)) + "." + parts[1];

        assertError(list(fixture.actor(), 1, changed), 400, "INVALID_CURSOR");
    }

    @Test
    void deletedBoundary() throws Exception {
        Listing fixture = listing(5);
        Page first = page(list(fixture.actor(), 2, null));
        BookmarkRow boundary = fixture.rows().get(1);

        MockHttpServletResponse removed = remove(boundary.getWorkId(), fixture.owner());
        assertEquals(200, removed.getStatus(), removed.getContentAsString());
        Page next = page(list(fixture.actor(), 2, first.cursor()));

        assertEquals(workIds(fixture.rows().subList(2, 4)), ids(next));
        assertEquals(0L, social.bookmarked(boundary.getWorkId(), fixture.actor().user()));
        assertNull(works.get(boundary.getWorkId()));
    }

    @Test
    void newerBookmark() throws Exception {
        Listing fixture = listing(5);
        Page first = page(list(fixture.actor(), 2, null));
        WorkRow added = work(fixture.owner(), "PUBLISHED");
        bookmark(added, fixture.actor(), Values.now());

        Page next = page(list(fixture.actor(), 2, first.cursor()));

        assertEquals(workIds(fixture.rows().subList(2, 4)), ids(next));
        Page refreshed = page(list(fixture.actor(), 2, null));
        assertEquals(added.getId(), ids(refreshed).get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "private"})
    void changesDuringRead(String change) throws Exception {
        Listing fixture = listing(3);
        SocialMapper actual = sql.getMapper(SocialMapper.class);
        doAnswer(invocation -> {
            List<BookmarkRow> rows = actual.bookmarkPage(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
            assertEquals(3, rows.size());
            // 完成真实关联查询后，再改变作品，复现查询收藏与读取作品之间的并发窗口。
            for (BookmarkRow row : rows.subList(0, 2)) {
                if ("deleted".equals(change)) {
                    assertEquals(200, workService.delete(new Viewer(fixture.owner().user(), fixture.owner().session().getId()),
                            row.getWorkId(), "\"v7\"").status());
                } else {
                    jdbc.update("UPDATE works SET status = 'DRAFT' WHERE id = ?", row.getWorkId());
                }
            }
            return rows;
        }).when(social).bookmarkPage(eq(fixture.actor().user()), anyLong(), anyString(), anyInt());

        Page first = page(list(fixture.actor(), 2, null));

        assertEquals(List.of(), first.items());
        assertTrue(first.more());
        assertCursor(first, fixture.actor(), fixture.rows().get(1));
        doAnswer(invocation -> actual.bookmarkPage(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3)))
                .when(social).bookmarkPage(eq(fixture.actor().user()), anyLong(), anyString(), anyInt());
        Page last = page(list(fixture.actor(), 2, first.cursor()));
        assertEquals(List.of(fixture.rows().get(2).getWorkId()), ids(last));
        assertFalse(last.more());
        assertNull(last.cursor());
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '收藏用户', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "bookmark_" + user.replace("-", "").substring(0, 12), CREATED);
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
        return work(owner, status, 0);
    }

    private WorkRow work(Account owner, String status, long offset) {
        var row = new WorkRow();
        row.setId(Values.id());
        row.setOwnerId(owner.user());
        row.setKind("TEXT");
        row.setStatus(status);
        row.setVersion(7);
        row.setTitle("收藏作品");
        row.setBody("正文");
        row.setCreatedAt(CREATED + offset);
        row.setUpdatedAt(CREATED + offset + 1000);
        if ("PUBLISHED".equals(status)) row.setPublishedAt(CREATED + offset + 100_000);
        if ("FAILED".equals(status)) row.setFailureCode("MEDIA_PROCESSING_FAILED");
        works.insert(row);
        return row;
    }

    private BookmarkRow bookmark(WorkRow work, Account actor, long at) {
        var row = new BookmarkRow();
        row.setId(Values.id());
        row.setWorkId(work.getId());
        row.setCreatedAt(at);
        jdbc.update("INSERT INTO bookmarks (id, user_id, work_id, created_at) VALUES (?, ?, ?, ?)",
                row.getId(), actor.user(), row.getWorkId(), at);
        return row;
    }

    private Listing listing(int count) {
        Account owner = account();
        Account actor = account();
        var rows = new ArrayList<BookmarkRow>();
        for (int i = 0; i < count; i++) {
            // 收藏时间与作品时间采用相反顺序，并制造同时间戳，检验收藏关系自身的排序键。
            WorkRow work = work(owner, "PUBLISHED", (count - i) * 1000L);
            rows.add(bookmark(work, actor, CREATED + (i / 3) * 1000L));
        }
        rows.sort(Comparator.comparingLong(BookmarkRow::getCreatedAt).reversed()
                .thenComparing(BookmarkRow::getId, Comparator.reverseOrder()));
        return new Listing(actor, owner, rows);
    }

    private void seedLike(WorkRow row, Account actor) {
        jdbc.update("INSERT INTO work_likes (user_id, work_id, created_at) VALUES (?, ?, ?)",
                actor.user(), row.getId(), CREATED);
    }

    private List<Map<String, Object>> likes(WorkRow row) {
        return jdbc.queryForList("SELECT * FROM work_likes WHERE work_id = ? ORDER BY user_id", row.getId());
    }

    private List<Map<String, Object>> bookmarks(WorkRow row) {
        return jdbc.queryForList("SELECT * FROM bookmarks WHERE work_id = ? ORDER BY id", row.getId());
    }

    private List<String> workIds(List<BookmarkRow> rows) {
        return rows.stream().map(BookmarkRow::getWorkId).toList();
    }

    private List<String> ids(Page page) {
        return page.items().stream().map(item -> (String) item.get("id")).toList();
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

    private MockHttpServletResponse change(WorkRow work, Account actor, boolean enabled) throws Exception {
        return perform(request(work.getId(), actor.access(), enabled));
    }

    private MockHttpServletRequestBuilder request(String work, String token, boolean enabled) {
        String path = WORKS + work + "/bookmark";
        MockHttpServletRequestBuilder request = enabled ? put(path) : delete(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private MockHttpServletResponse list(Account actor, Integer limit, String cursor) throws Exception {
        var request = get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access());
        if (limit != null) request.param("limit", limit.toString());
        if (cursor != null) request.param("cursor", cursor);
        return perform(request);
    }

    private MockHttpServletResponse remove(String work, Account owner) throws Exception {
        return perform(delete(WORKS + work).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
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
        return mvc.perform(request.header("X-Request-Id", "bookmark-" + Values.id())).andReturn().getResponse();
    }

    private void state(MockHttpServletResponse response, WorkRow row, boolean liked, boolean bookmarked,
                       long likeCount, long bookmarkCount) throws Exception {
        Map<String, Object> result = data(response);
        assertEquals(Set.of("workId", "liked", "bookmarked", "likeCount", "bookmarkCount"), result.keySet());
        assertEquals(row.getId(), result.get("workId"));
        assertEquals(liked, result.get("liked"));
        assertEquals(bookmarked, result.get("bookmarked"));
        assertEquals(likeCount, ((Number) result.get("likeCount")).longValue());
        assertEquals(bookmarkCount, ((Number) result.get("bookmarkCount")).longValue());
    }

    private Page page(MockHttpServletResponse response) throws Exception {
        Map<String, Object> result = data(response);
        assertEquals(Set.of("items", "nextCursor", "hasMore"), result.keySet());
        List<?> raw = assertInstanceOf(List.class, result.get("items"));
        List<Map<String, Object>> items = raw.stream().map(this::object).toList();
        for (Map<String, Object> item : items) {
            assertEquals(CARD_FIELDS, item.keySet());
            assertEquals("PUBLISHED", item.get("status"));
            assertEquals(Boolean.TRUE, object(item.get("viewer")).get("bookmarked"));
            assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount", "createdAt"),
                    object(item.get("author")).keySet());
        }
        boolean more = assertInstanceOf(Boolean.class, result.get("hasMore"));
        String cursor = (String) result.get("nextCursor");
        if (more) assertNotNull(cursor);
        else assertNull(cursor);
        return new Page(items, cursor, more);
    }

    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("bookmark-"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        return object(body.get("data"));
    }

    private void assertCursor(Page result, Account actor, BookmarkRow last) {
        Map<String, Object> claims = crypto.open("cursor", result.cursor(), "INVALID_CURSOR");
        assertEquals("/me/bookmarks", claims.get("route"));
        assertEquals(actor.user(), claims.get("filter"));
        assertEquals(last.getId(), claims.get("id"));
        assertEquals(last.getCreatedAt(), ((Number) claims.get("time")).longValue());
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
    private record Listing(Account actor, Account owner, List<BookmarkRow> rows) {}
    private record Page(List<Map<String, Object>> items, String cursor, boolean more) {}
}
