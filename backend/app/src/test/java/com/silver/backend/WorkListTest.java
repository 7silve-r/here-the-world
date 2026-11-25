package com.silver.backend;

import com.silver.backend.business.feed.Pages;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
@TestPropertySource(properties = "htw.public-api-base-url=https://api.example.test/api/v1")
class WorkListTest extends BackendTestSupport {
    private static final String MINE = "/api/v1/me/works";
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;
    private static final List<String> STATES = List.of("DRAFT", "PROCESSING", "PUBLISHED", "FAILED");
    private static final Set<String> CARD_FIELDS = Set.of(
            "id", "kind", "status", "version", "title", "excerpt", "author", "cover", "stats",
            "viewer", "createdAt", "publishedAt");
    private static final Set<String> AUTHOR_FIELDS = Set.of(
            "id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount", "createdAt");

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private Pages pages;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;

    private final List<String> userIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String id : workIds) {
            jdbc.update("DELETE FROM work_likes WHERE work_id = ?", id);
            jdbc.update("DELETE FROM bookmarks WHERE work_id = ?", id);
            jdbc.update("DELETE FROM comments WHERE work_id = ?", id);
            jdbc.update("DELETE FROM work_assets WHERE work_id = ?", id);
            jdbc.update("DELETE FROM works WHERE id = ?", id);
        }
        for (String id : assetIds) jdbc.update("DELETE FROM assets WHERE id = ?", id);
        for (String id : userIds) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void publicScope() throws Exception {
        Account owner = account();
        List<WorkRow> published = seed(owner, "PUBLISHED", 3);
        for (String state : List.of("DRAFT", "PROCESSING", "FAILED")) seed(owner, state, 2);
        seed(account(), "PUBLISHED", 2);

        Page result = page(perform(get(publicPath(owner)).param("status", "DRAFT").param("mine", "true")));

        assertEquals(ids(sorted(published, false)), ids(result));
        assertFalse(result.more());
        assertTrue(result.items().stream().allMatch(item -> "PUBLISHED".equals(item.get("status"))));
        assertTrue(result.items().stream().allMatch(item -> owner.user().equals(object(item.get("author")).get("id"))));
    }

    @Test
    void myScope() throws Exception {
        Account owner = account();
        var expected = new ArrayList<WorkRow>();
        for (String state : STATES) expected.addAll(seed(owner, state, 1));
        Account other = account();
        for (String state : STATES) seed(other, state, 1);
        SessionRow before = users.session(owner.session().getId());

        Page result = page(perform(get(MINE).param("owner", other.user()).param("userId", other.user())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())));

        assertEquals(ids(sorted(expected, true)), ids(result));
        assertEquals(new HashSet<>(STATES), result.items().stream().map(item -> item.get("status"))
                .collect(java.util.stream.Collectors.toSet()));
        for (Map<String, Object> item : result.items()) {
            assertEquals(owner.user(), object(item.get("author")).get("id"));
            assertEquals(Boolean.TRUE, object(item.get("viewer")).get("isOwner"));
        }
        assertEquals(before, users.session(before.getId()), "读取列表不应轮换或延长会话");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PROCESSING", "PUBLISHED", "FAILED"})
    void filtersState(String state) throws Exception {
        Account owner = account();
        List<WorkRow> expected = List.of();
        for (String candidate : STATES) {
            List<WorkRow> rows = seed(owner, candidate, 2);
            if (state.equals(candidate)) expected = rows;
        }
        seed(account(), state, 1);

        Page result = page(list(owner, true, 10, null, state));

        assertEquals(ids(sorted(expected, true)), ids(result));
        assertTrue(result.items().stream().allMatch(item -> state.equals(item.get("status"))));
        assertFalse(result.more());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyList(boolean mine) throws Exception {
        Page result = page(list(account(), mine, null, null, null));

        assertEquals(List.of(), result.items());
        assertFalse(result.more());
        assertNull(result.cursor());
    }

    @Test
    void privateOnly() throws Exception {
        Account owner = account();
        for (String state : List.of("DRAFT", "PROCESSING", "FAILED")) seed(owner, state, 1);

        Page result = page(list(owner, false, 10, null, null));

        assertEquals(List.of(), result.items());
        assertFalse(result.more());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingUser(boolean authenticated) throws Exception {
        var request = get("/api/v1/users/" + Values.id() + "/works");
        if (authenticated) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account().access());

        assertError(perform(request), 404, "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void needsBearer(boolean cookies) throws Exception {
        Account owner = account();
        seed(owner, "DRAFT", 1);
        var request = get(MINE);
        if (cookies) {
            request.cookie(new Cookie("htw_refresh", crypto.randomToken()),
                    new Cookie("htw_media", crypto.ticket("media-read",
                            Values.map("user", owner.user(), "session", owner.session().getId()), 60_000)));
        }

        assertError(perform(request), 401, "AUTH_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "revoked"})
    void badBearer(String kind) throws Exception {
        Account owner = account();
        String token = "invalid-token";
        String code = "TOKEN_INVALID";
        if ("revoked".equals(kind)) {
            token = owner.access();
            code = "SESSION_REVOKED";
            jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", owner.session().getId());
        }

        assertError(perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 401, code);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultLimit(boolean mine) throws Exception {
        Account owner = account();
        List<WorkRow> expected = sorted(seed(owner, "PUBLISHED", 26), mine);

        Page result = page(list(owner, mine, null, null, null));

        assertEquals(ids(expected.subList(0, 24)), ids(result));
        assertTrue(result.more());
        assertCursor(result, owner, mine, null, expected.get(23));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void maximumLimit(boolean mine) throws Exception {
        Account owner = account();
        List<WorkRow> expected = sorted(seed(owner, "PUBLISHED", 51), mine);

        Page result = page(list(owner, mine, 50, null, null));

        assertEquals(ids(expected.subList(0, 50)), ids(result));
        assertTrue(result.more());
        assertCursor(result, owner, mine, null, expected.get(49));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void badLimit(int limit) throws Exception {
        Account owner = account();

        assertError(list(owner, true, limit, null, null), 400, "BAD_REQUEST");
        assertError(list(owner, false, limit, null, null), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2147483648"})
    void badLimitType(String limit) throws Exception {
        assertError(perform(get(MINE).param("limit", limit)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account().access())), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "draft", "UNKNOWN"})
    void badStatus(String status) throws Exception {
        assertError(list(account(), true, 2, null, status), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void walksPages(boolean mine) throws Exception {
        Account owner = account();
        List<WorkRow> expected = sorted(seed(owner, "PUBLISHED", 9), mine);
        var seen = new HashSet<String>();
        var collected = new ArrayList<String>();
        String cursor = null;
        for (int offset = 0; offset < expected.size(); offset += 2) {
            Page result = page(list(owner, mine, 2, cursor, null));
            int end = Math.min(offset + 2, expected.size());
            assertEquals(ids(expected.subList(offset, end)), ids(result));
            for (String id : ids(result)) assertTrue(seen.add(id), "翻页不能重复返回同一作品");
            collected.addAll(ids(result));
            assertEquals(end < expected.size(), result.more());
            if (result.more()) {
                assertCursor(result, owner, mine, null, expected.get(end - 1));
                assertNotEquals(cursor, result.cursor());
            }
            cursor = result.cursor();
        }
        assertEquals(ids(expected), collected);
        assertNull(cursor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactLastPage(boolean mine) throws Exception {
        Account owner = account();
        List<WorkRow> expected = sorted(seed(owner, "PUBLISHED", 4), mine);
        Page first = page(list(owner, mine, 2, null, null));

        Page last = page(list(owner, mine, 2, first.cursor(), null));

        assertEquals(ids(expected.subList(2, 4)), ids(last));
        assertFalse(last.more());
        assertNull(last.cursor());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedBoundary(boolean mine) throws Exception {
        Account owner = account();
        List<WorkRow> expected = sorted(seed(owner, "PUBLISHED", 5), mine);
        Page first = page(list(owner, mine, 2, null, null));
        WorkRow boundary = expected.get(1);
        assertEquals(1, works.delete(boundary.getId(), boundary.getVersion()));

        Page next = page(list(owner, mine, 2, first.cursor(), null));

        assertEquals(ids(expected.subList(2, 4)), ids(next));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newerWork(boolean mine) throws Exception {
        Account owner = account();
        List<WorkRow> expected = sorted(seed(owner, "PUBLISHED", 5), mine);
        Page first = page(list(owner, mine, 2, null, null));
        WorkRow added = work(owner, "PUBLISHED", CREATED + 999_999, CREATED + 999_999);

        Page next = page(list(owner, mine, 2, first.cursor(), null));

        assertEquals(ids(expected.subList(2, 4)), ids(next));
        Page refreshed = page(list(owner, mine, 2, null, null));
        assertEquals(added.getId(), ids(refreshed).get(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossRoute(boolean fromMine) throws Exception {
        Account owner = account();
        seed(owner, "PUBLISHED", 3);
        Page first = page(list(owner, fromMine, 1, null, null));

        assertError(list(owner, !fromMine, 1, first.cursor(), null), 400, "INVALID_CURSOR");
    }

    @Test
    void anotherUserCursor() throws Exception {
        Account first = account();
        Account second = account();
        seed(first, "DRAFT", 3);
        seed(second, "DRAFT", 1);
        Page source = page(list(first, true, 1, null, "DRAFT"));

        assertError(list(second, true, 1, source.cursor(), "DRAFT"), 400, "INVALID_CURSOR");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedFilter(boolean removeStatus) throws Exception {
        Account owner = account();
        seed(owner, "DRAFT", 3);
        seed(owner, "PUBLISHED", 1);
        Page first = page(list(owner, true, 1, null, "DRAFT"));

        assertError(list(owner, true, 1, first.cursor(), removeStatus ? null : "PUBLISHED"),
                400, "INVALID_CURSOR");
    }

    @Test
    void otherPublicCursor() throws Exception {
        Account first = account();
        Account second = account();
        seed(first, "PUBLISHED", 3);
        Page source = page(list(first, false, 1, null, null));

        assertError(list(second, false, 1, source.cursor(), null), 400, "INVALID_CURSOR");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void badCursor(boolean mine) throws Exception {
        Account owner = account();

        assertError(list(owner, mine, 2, "", null), 400, "INVALID_CURSOR");
        assertError(list(owner, mine, 2, "invalid-cursor", null), 400, "INVALID_CURSOR");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredCursor(boolean mine) throws Exception {
        Account owner = account();
        String route = mine ? "/me/works" : "/users/" + owner.user() + "/works";
        String cursor = crypto.ticket("cursor", Values.map("route", route, "filter", owner.user() + ":",
                "time", CREATED, "id", Values.id()), -60_000);

        assertError(list(owner, mine, 2, cursor, null), 400, "INVALID_CURSOR");
    }

    @Test
    void tamperedCursor() throws Exception {
        Account owner = account();
        seed(owner, "PUBLISHED", 3);
        Page first = page(list(owner, true, 1, null, null));
        String[] parts = first.cursor().split("\\.", -1);
        Map<String, Object> claims = json.readMap(new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        claims.put("time", CREATED + 999_999);
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.write(claims).getBytes(StandardCharsets.UTF_8)) + "." + parts[1];

        assertError(list(owner, true, 2, changed, null), 400, "INVALID_CURSOR");
    }

    @Test
    void feedCursor() throws Exception {
        Account owner = account();
        String cursor = pages.feed("feed-session", 23);

        assertError(list(owner, true, 2, cursor, null), 400, "INVALID_CURSOR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "owner", "other"})
    void cardSummary(String role) throws Exception {
        Account owner = account();
        Account other = account();
        WorkRow row = work(owner, "PUBLISHED", CREATED, CREATED + 1000);
        String body = "😀".repeat(281) + "完整正文不应出现在卡片中";
        jdbc.update("UPDATE works SET body = ? WHERE id = ?", body, row.getId());
        String cover = cover(owner);
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", cover, row.getId());
        interactions(row, owner, other);
        String token = switch (role) {
            case "owner" -> owner.access();
            case "other" -> other.access();
            default -> null;
        };
        WorkRow before = works.get(row.getId());

        Page result = page(perform(request(publicPath(owner), token, 2, null, null)));

        assertEquals(List.of(row.getId()), ids(result));
        Map<String, Object> card = result.items().get(0);
        assertEquals("😀".repeat(280), card.get("excerpt"));
        assertEquals("PUBLISHED", card.get("status"));
        assertEquals(7L, ((Number) card.get("version")).longValue());
        assertEquals(row.getTitle(), card.get("title"));
        assertEquals(Values.time(row.getCreatedAt()), card.get("createdAt"));
        assertEquals(Values.time(row.getPublishedAt()), card.get("publishedAt"));
        assertEquals(Map.of("likeCount", 2, "bookmarkCount", 1, "commentCount", 2), card.get("stats"));
        assertEquals(Map.of("liked", !"guest".equals(role), "bookmarked", "other".equals(role),
                "isOwner", "owner".equals(role)), card.get("viewer"));
        assertEquals(Values.map("url", BASE + "/media/public/assets/" + cover + "/thumbnail",
                "width", 640, "height", 480, "blurHash", null), object(card.get("cover")));
        Map<String, Object> author = object(card.get("author"));
        assertEquals(owner.user(), author.get("id"));
        assertEquals(1L, ((Number) author.get("publishedWorkCount")).longValue());
        assertEquals(before, works.get(row.getId()), "读取卡片不应修改作品或版本");
    }

    @Test
    void draftCard() throws Exception {
        Account owner = account();
        WorkRow row = work(owner, "DRAFT", CREATED, 0);
        String cover = cover(owner);
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", cover, row.getId());

        Page result = page(list(owner, true, 2, null, "DRAFT"));

        Map<String, Object> card = result.items().get(0);
        assertEquals("DRAFT", card.get("status"));
        assertNull(card.get("publishedAt"));
        assertEquals(BASE + "/media/private/assets/" + cover + "/thumbnail", object(card.get("cover")).get("url"));
        assertEquals(Map.of("liked", false, "bookmarked", false, "isOwner", true), card.get("viewer"));
        assertEquals(0L, ((Number) object(card.get("author")).get("publishedWorkCount")).longValue());
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '列表作者', '个人简介', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "list_" + user.replace("-", "").substring(0, 12), CREATED);
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

    private WorkRow work(Account owner, String state, long created, long published) {
        var row = new WorkRow();
        row.setId(Values.id());
        row.setOwnerId(owner.user());
        row.setKind("TEXT");
        row.setStatus(state);
        row.setVersion(7);
        row.setTitle("列表作品");
        row.setBody("作品的正文");
        row.setCreatedAt(created);
        row.setUpdatedAt(created + 1000);
        if ("PUBLISHED".equals(state)) row.setPublishedAt(published);
        if ("FAILED".equals(state)) row.setFailureCode("MEDIA_PROCESSING_FAILED");
        works.insert(row);
        workIds.add(row.getId());
        return row;
    }

    private List<WorkRow> seed(Account owner, String state, int count) {
        var rows = new ArrayList<WorkRow>();
        for (int i = 0; i < count; i++) {
            // 两种时间采用不同的顺序，并包含相同时间戳，分别检验两种 SQL 的排序键。
            rows.add(work(owner, state, CREATED + (i / 3) * 1000L,
                    CREATED + 100_000 + ((count - 1 - i) / 2) * 1000L));
        }
        return rows;
    }

    private List<WorkRow> sorted(List<WorkRow> rows, boolean mine) {
        return rows.stream().sorted(Comparator
                .comparingLong((WorkRow row) -> mine ? row.getCreatedAt() : row.getPublishedAt()).reversed()
                .thenComparing(WorkRow::getId, Comparator.reverseOrder())).toList();
    }

    private List<String> ids(List<WorkRow> rows) {
        return rows.stream().map(WorkRow::getId).toList();
    }

    private List<String> ids(Page page) {
        return page.items().stream().map(item -> (String) item.get("id")).toList();
    }

    private String cover(Account owner) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, thumbnail_key, size_bytes, width, height, created_at)
                VALUES (?, ?, 'WORK_COVER', 'IMAGE', 'READY', 'image/jpeg', ?, ?, 1024, 640, 480, ?)
                """, id, owner.user(), "original/" + id, "thumbnails/" + id + ".jpg", CREATED);
        assetIds.add(id);
        return id;
    }

    private void interactions(WorkRow row, Account owner, Account other) {
        for (Account actor : List.of(owner, other)) {
            jdbc.update("INSERT INTO work_likes (user_id, work_id, created_at) VALUES (?, ?, ?)",
                    actor.user(), row.getId(), CREATED);
            jdbc.update("INSERT INTO comments (id, work_id, user_id, body, created_at) VALUES (?, ?, ?, '评论', ?)",
                    Values.id(), row.getId(), actor.user(), CREATED);
        }
        jdbc.update("INSERT INTO bookmarks (id, user_id, work_id, created_at) VALUES (?, ?, ?, ?)",
                Values.id(), other.user(), row.getId(), CREATED);
    }

    private String publicPath(Account owner) {
        return "/api/v1/users/" + owner.user() + "/works";
    }

    private MockHttpServletResponse list(Account owner, boolean mine, Integer limit, String cursor, String status)
            throws Exception {
        return perform(request(mine ? MINE : publicPath(owner), mine ? owner.access() : null, limit, cursor, status));
    }

    private MockHttpServletRequestBuilder request(String path, String token, Integer limit, String cursor, String status) {
        var request = get(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (limit != null) request.param("limit", limit.toString());
        if (cursor != null) request.param("cursor", cursor);
        if (status != null) request.param("status", status);
        return request;
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "work-list-" + Values.id()))
                .andReturn().getResponse();
    }

    private Page page(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-list-"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        Map<String, Object> data = object(body.get("data"));
        assertEquals(Set.of("items", "nextCursor", "hasMore"), data.keySet());
        List<?> raw = assertInstanceOf(List.class, data.get("items"));
        List<Map<String, Object>> items = raw.stream().map(this::object).toList();
        for (Map<String, Object> item : items) {
            assertEquals(CARD_FIELDS, item.keySet());
            assertEquals(AUTHOR_FIELDS, object(item.get("author")).keySet());
            assertEquals(Set.of("likeCount", "bookmarkCount", "commentCount"), object(item.get("stats")).keySet());
            assertEquals(Set.of("liked", "bookmarked", "isOwner"), object(item.get("viewer")).keySet());
        }
        boolean more = assertInstanceOf(Boolean.class, data.get("hasMore"));
        String cursor = (String) data.get("nextCursor");
        if (more) assertNotNull(cursor);
        else assertNull(cursor);
        return new Page(items, cursor, more);
    }

    private void assertCursor(Page result, Account owner, boolean mine, String status, WorkRow last) {
        String route = mine ? "/me/works" : "/users/" + owner.user() + "/works";
        Map<String, Object> claims = pages.open(result.cursor(), route, owner.user() + ":" + (status == null ? "" : status));
        assertEquals(last.getId(), claims.get("id"));
        assertEquals(mine ? last.getCreatedAt() : last.getPublishedAt(), ((Number) claims.get("time")).longValue());
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
    private record Page(List<Map<String, Object>> items, String cursor, boolean more) {}
}
