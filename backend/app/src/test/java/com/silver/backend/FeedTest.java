package com.silver.backend;

import com.silver.backend.business.persistence.FeedItemRow;
import com.silver.backend.business.persistence.FeedRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.FeedMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:feed_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "htw.public-api-base-url=https://api.example.test/api/v1"
})
class FeedTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/feed";
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;
    private static final long TTL = 1_800_000L;
    private static final Set<String> CARD_FIELDS = Set.of(
            "id", "kind", "status", "version", "title", "excerpt", "author", "cover", "stats",
            "viewer", "createdAt", "publishedAt");

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private FeedMapper feeds;

    private final List<String> userIds = new ArrayList<>();

    @BeforeEach
    void clearFeeds() {
        jdbc.update("DELETE FROM feed_items");
        jdbc.update("DELETE FROM feed_sessions");
    }

    @AfterEach
    void cleanFixtures() {
        clearFeeds();
        for (String user : userIds) {
            for (String table : List.of("work_likes", "bookmarks", "comments", "work_assets")) {
                jdbc.update("DELETE FROM " + table + " WHERE work_id IN (SELECT id FROM works WHERE owner_id = ?)", user);
            }
            jdbc.update("DELETE FROM works WHERE owner_id = ?", user);
        }
        for (String user : userIds) {
            jdbc.update("DELETE FROM assets WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void emptyFeed() throws Exception {
        long before = Values.now();

        Page result = page(read(null, null, null));

        assertEquals(List.of(), result.items());
        assertFalse(result.more());
        assertNull(result.cursor());
        assertEquals(1, sessionCount());
        assertTrue(entries(result.session()).isEmpty());
        assertLifetime(result, before);
    }

    @Test
    void createsSnapshot() throws Exception {
        Account owner = account();
        var expected = new ArrayList<>(seed(owner, "PUBLISHED", 4));
        expected.addAll(seed(account(), "PUBLISHED", 2));
        for (String state : List.of("DRAFT", "PROCESSING", "FAILED")) seed(owner, state, 1);
        String future = seed(owner, "PUBLISHED", 1).get(0);
        jdbc.update("UPDATE works SET published_at = ? WHERE id = ?", Long.MAX_VALUE, future);
        long before = Values.now();

        Page result = page(read(2, null, null));

        List<FeedItemRow> saved = entries(result.session());
        assertEquals(new HashSet<>(expected), new HashSet<>(entryIds(saved)));
        assertEquals(expected.size(), saved.size());
        for (int i = 0; i < saved.size(); i++) assertEquals(i, saved.get(i).getPosition());
        assertEquals(entryIds(saved.subList(0, 2)), ids(result));
        assertTrue(result.more());
        assertCursor(result, 1);
        assertLifetime(result, before);
    }

    @Test
    void cutoffBoundary() {
        Account owner = account();
        List<String> ids = seed(owner, "PUBLISHED", 3);
        long cutoff = CREATED + 1000;
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE works SET published_at = ? WHERE id = ?", cutoff + i - 1, ids.get(i));
        }
        seed(owner, "DRAFT", 1);

        assertEquals(Set.copyOf(ids.subList(0, 2)), Set.copyOf(works.candidates(cutoff)));
    }

    @Test
    void capsSnapshot() throws Exception {
        Account owner = account();
        Set<String> candidates = Set.copyOf(seed(owner, "PUBLISHED", 2005));

        Page first = page(read(1, null, null));

        List<FeedItemRow> saved = entries(first.session());
        assertEquals(2000, saved.size());
        assertEquals(2000, new HashSet<>(entryIds(saved)).size());
        assertTrue(candidates.containsAll(entryIds(saved)));
        for (int i = 0; i < saved.size(); i++) assertEquals(i, saved.get(i).getPosition());
        // 直接续查快照末尾，验证到达上限后正常结束，不必组装全部 2000 个卡片。
        Page tail = page(read(50, cursor(first.session(), 1974), null));
        assertEquals(entryIds(saved.subList(1975, 2000)), ids(tail));
        assertFalse(tail.more());
        assertNull(tail.cursor());
    }

    @Test
    void rollsBack() throws Exception {
        List<String> candidates = seed(account(), "PUBLISHED", 3);
        FeedMapper actual = sql.getMapper(FeedMapper.class);
        doAnswer(invocation -> {
            String session = invocation.getArgument(0);
            long position = invocation.getArgument(1);
            actual.item(session, position, invocation.getArgument(2));
            if (position == 1) {
                assertEquals(1, sessionCount());
                assertEquals(2, entries(session).size());
                throw new IllegalStateException("Failure after persisting snapshot items");
            }
            return null;
        }).when(feeds).item(anyString(), anyLong(), anyString());

        assertError(read(2, null, null), 500, "INTERNAL_ERROR");

        assertEquals(0, sessionCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feed_items", Integer.class));
        assertEquals(Set.copyOf(candidates), Set.copyOf(works.candidates(Values.now())));
        doAnswer(invocation -> {
            actual.item(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(feeds).item(anyString(), anyLong(), anyString());
        Page retry = page(read(2, null, null));
        assertEquals(1, sessionCount());
        assertEquals(3, entries(retry.session()).size());
    }

    @Test
    void newSession() throws Exception {
        seed(account(), "PUBLISHED", 4);
        Page first = page(read(1, null, null));
        List<FeedItemRow> before = entries(first.session());

        Page second = page(read(1, null, null));

        assertNotEquals(first.session(), second.session());
        assertEquals(2, sessionCount());
        assertEquals(before, entries(first.session()));
        assertEquals(Set.copyOf(entryIds(before)), Set.copyOf(entryIds(entries(second.session()))));
        // 随机排列允许偶然相同；这里只断言每个会话独立持久化。
    }

    @Test
    void defaultLimit() throws Exception {
        seed(account(), "PUBLISHED", 26);

        Page result = page(read(null, null, null));

        assertEquals(24, result.items().size());
        assertEquals(entryIds(entries(result.session()).subList(0, 24)), ids(result));
        assertTrue(result.more());
        assertCursor(result, 23);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void limitBounds(int limit) throws Exception {
        seed(account(), "PUBLISHED", limit + 1);

        Page result = page(read(limit, null, null));

        assertEquals(limit, result.items().size());
        assertTrue(result.more());
        assertCursor(result, limit - 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 51})
    void badLimit(int limit) throws Exception {
        assertError(read(limit, null, null), 400, "BAD_REQUEST");
        assertEquals(0, sessionCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2147483648"})
    void badLimitType(String limit) throws Exception {
        assertError(perform(get(PATH).param("limit", limit)), 400, "BAD_REQUEST");
        assertEquals(0, sessionCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "a.b.c", "%%.%%"})
    void badCursor(String cursor) throws Exception {
        assertError(read(2, cursor, null), 400, "INVALID_CURSOR");
        assertEquals(0, sessionCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"route", "filter", "purpose"})
    void wrongScope(String field) throws Exception {
        FeedRow row = snapshot(seed(account(), "PUBLISHED", 3));
        Map<String, Object> claims = claims(row.getId(), -1);
        if ("route".equals(field)) claims.put("route", "/me/works");
        if ("filter".equals(field)) claims.put("filter", "PUBLISHED");
        String cursor = crypto.ticket("purpose".equals(field) ? "upload" : "cursor", claims, 60_000);

        assertError(read(2, cursor, null), 400, "INVALID_CURSOR");

        assertEquals(1, sessionCount());
        assertEquals(row, feeds.get(row.getId()));
    }

    @Test
    void tamperedCursor() throws Exception {
        FeedRow row = snapshot(seed(account(), "PUBLISHED", 3));
        String original = cursor(row.getId(), -1);
        String[] parts = original.split("\\.", -1);
        Map<String, Object> claims = json.readMap(new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        claims.put("position", 100);
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.write(claims).getBytes(StandardCharsets.UTF_8)) + "." + parts[1];

        assertError(read(2, changed, null), 400, "INVALID_CURSOR");

        assertEquals(row, feeds.get(row.getId()));
    }

    @Test
    void walksSnapshot() throws Exception {
        seed(account(), "PUBLISHED", 11);
        Page current = page(read(3, null, null));
        String session = current.session();
        List<FeedItemRow> saved = entries(session);
        FeedRow before = feeds.get(session);
        var collected = new ArrayList<String>();
        var seen = new HashSet<String>();
        int offset = 0;
        while (true) {
            int end = Math.min(offset + 3, saved.size());
            assertEquals(entryIds(saved.subList(offset, end)), ids(current));
            assertEquals(session, current.session());
            assertEquals(Values.time(before.getExpiresAt()), current.expiresAt());
            for (String id : ids(current)) assertTrue(seen.add(id), "同一快照跨页不能重复返回作品");
            collected.addAll(ids(current));
            assertEquals(end < saved.size(), current.more());
            if (!current.more()) break;
            assertCursor(current, end - 1);
            offset = end;
            current = page(read(3, current.cursor(), null));
        }
        assertEquals(entryIds(saved), collected);
        assertEquals(saved, entries(session));
        assertEquals(before, feeds.get(session), "翻页不应改变截止时间或延长会话");
        assertEquals(1, sessionCount());
    }

    @Test
    void repeatsCursor() throws Exception {
        seed(account(), "PUBLISHED", 6);
        Page first = page(read(2, null, null));

        Page one = page(read(2, first.cursor(), null));
        Page two = page(read(2, first.cursor(), null));

        assertEquals(one.items(), two.items());
        assertEquals(one.session(), two.session());
        assertEquals(one.expiresAt(), two.expiresAt());
        assertEquals(one.more(), two.more());
        assertCursor(one, 3);
        assertCursor(two, 3);
        assertEquals(1, sessionCount());
    }

    @Test
    void changesLimit() throws Exception {
        List<String> ids = seed(account(), "PUBLISHED", 9);
        FeedRow row = snapshot(ids);

        Page first = page(read(2, cursor(row.getId(), -1), null));
        Page second = page(read(4, first.cursor(), null));
        Page last = page(read(3, second.cursor(), null));

        assertEquals(ids.subList(0, 2), ids(first));
        assertEquals(ids.subList(2, 6), ids(second));
        assertEquals(ids.subList(6, 9), ids(last));
        assertCursor(first, 1);
        assertCursor(second, 5);
        assertFalse(last.more());
        assertNull(last.cursor());
    }

    @Test
    void skipsInvalid() throws Exception {
        List<String> ids = seed(account(), "PUBLISHED", 8);
        FeedRow row = snapshot(ids);
        jdbc.update("DELETE FROM works WHERE id IN (?, ?)", ids.get(0), ids.get(6));
        jdbc.update("UPDATE works SET status = 'DRAFT' WHERE id = ?", ids.get(1));
        jdbc.update("UPDATE works SET status = 'FAILED' WHERE id = ?", ids.get(3));
        jdbc.update("UPDATE works SET status = 'PROCESSING' WHERE id = ?", ids.get(4));

        Page first = page(read(2, cursor(row.getId(), -1), null));

        assertEquals(List.of(ids.get(2), ids.get(5)), ids(first));
        assertCursor(first, 5); // 不能把扫描到的下一页作品位置 7 当作返回游标。
        Page last = page(read(2, first.cursor(), null));
        assertEquals(List.of(ids.get(7)), ids(last));
        assertFalse(last.more());
        assertEquals(8, entries(row.getId()).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24, 50})
    void scansBatches(int limit) throws Exception {
        List<String> valid = seed(account(), "PUBLISHED", limit + 1);
        var snapshotIds = new ArrayList<String>();
        int deleted = 2 * Math.max(32, limit + 1);
        for (int i = 0; i < deleted; i++) snapshotIds.add(Values.id());
        snapshotIds.addAll(valid);
        // 没有 work_id 外键，快照可保留已物理删除作品的 ID。
        FeedRow row = snapshot(snapshotIds);

        Page first = page(read(limit, cursor(row.getId(), -1), null));

        assertEquals(valid.subList(0, limit), ids(first));
        assertTrue(first.more());
        assertCursor(first, deleted + limit - 1);
        Page last = page(read(limit, first.cursor(), null));
        assertEquals(List.of(valid.get(limit)), ids(last));
        assertFalse(last.more());
    }

    @Test
    void invalidTail() throws Exception {
        List<String> valid = seed(account(), "PUBLISHED", 3);
        var snapshotIds = new ArrayList<>(valid);
        for (int i = 0; i < 70; i++) snapshotIds.add(Values.id());
        FeedRow row = snapshot(snapshotIds);

        Page result = page(read(3, cursor(row.getId(), -1), null));

        assertEquals(valid, ids(result));
        assertFalse(result.more(), "末尾只有失效记录时不应误报下一页");
        assertNull(result.cursor());
    }

    @Test
    void allInvalid() throws Exception {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 70; i++) ids.add(Values.id());
        FeedRow row = snapshot(ids);

        Page result = page(read(2, cursor(row.getId(), -1), null));

        assertEquals(List.of(), result.items());
        assertFalse(result.more());
        assertEquals(row.getId(), result.session());
        assertEquals(1, sessionCount());
    }

    @Test
    void exactLastPage() throws Exception {
        List<String> ids = seed(account(), "PUBLISHED", 4);
        FeedRow row = snapshot(ids);
        Page first = page(read(2, cursor(row.getId(), -1), null));

        Page last = page(read(2, first.cursor(), null));

        assertEquals(ids.subList(2, 4), ids(last));
        assertFalse(last.more());
        assertNull(last.cursor());
    }

    @ParameterizedTest
    @CsvSource({"expired,false", "expired,true", "missing,false", "missing,true"})
    void deadSession(String state, boolean oldTicket) throws Exception {
        FeedRow row = snapshot(seed(account(), "PUBLISHED", 3));
        String cursor = oldTicket
                ? crypto.ticket("cursor", claims(row.getId(), -1), -60_000)
                : cursor(row.getId(), -1);
        if ("expired".equals(state)) {
            jdbc.update("UPDATE feed_sessions SET expires_at = ? WHERE id = ?", Values.now() - 1_000, row.getId());
        } else {
            clearFeeds();
        }
        int count = sessionCount();

        assertError(read(2, cursor, null), 410, "FEED_SESSION_EXPIRED");

        assertEquals(count, sessionCount(), "失效游标不应静默创建新快照");
    }

    @Test
    void oldValidTicket() throws Exception {
        List<String> ids = seed(account(), "PUBLISHED", 3);
        FeedRow row = snapshot(ids);
        String old = crypto.ticket("cursor", claims(row.getId(), -1), -60_000);

        Page result = page(read(2, old, null));

        assertEquals(ids.subList(0, 2), ids(result));
        assertEquals(row, feeds.get(row.getId()));
    }

    @Test
    void excludesNewWork() throws Exception {
        Account owner = account();
        seed(owner, "PUBLISHED", 5);
        Page first = page(read(2, null, null));
        List<FeedItemRow> before = entries(first.session());
        FeedRow session = feeds.get(first.session());
        String added = seed(owner, "PUBLISHED", 1).get(0);
        jdbc.update("UPDATE works SET published_at = ? WHERE id = ?", session.getCutoff() + 1, added);

        Page second = page(read(2, first.cursor(), null));
        Page last = page(read(2, second.cursor(), null));

        assertEquals(entryIds(before.subList(2, 4)), ids(second));
        assertEquals(entryIds(before.subList(4, 5)), ids(last));
        assertFalse(ids(second).contains(added));
        assertFalse(ids(last).contains(added));
        assertEquals(before, entries(first.session()));
        assertFalse(last.more());
    }

    @Test
    void readsLiveCard() throws Exception {
        List<String> ids = seed(account(), "PUBLISHED", 3);
        FeedRow row = snapshot(ids);
        jdbc.update("UPDATE works SET title = '更新后的标题', version = 8 WHERE id = ?", ids.get(1));

        Page result = page(read(1, cursor(row.getId(), 0), null));

        Map<String, Object> card = result.items().get(0);
        assertEquals(ids.get(1), card.get("id"));
        assertEquals("更新后的标题", card.get("title"));
        assertEquals(8L, ((Number) card.get("version")).longValue());
        assertEquals(ids, entryIds(entries(row.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "owner", "other"})
    void viewerContext(String role) throws Exception {
        Account owner = account();
        Account other = account();
        List<String> ids = seed(owner, "PUBLISHED", 3);
        FeedRow row = snapshot(ids);
        String work = ids.get(1);
        jdbc.update("UPDATE works SET body = ? WHERE id = ?", "😀".repeat(281), work);
        String cover = cover(owner);
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", cover, work);
        jdbc.update("INSERT INTO work_likes (user_id, work_id, created_at) VALUES (?, ?, ?)", other.user(), work, CREATED);
        jdbc.update("INSERT INTO bookmarks (id, user_id, work_id, created_at) VALUES (?, ?, ?, ?)",
                Values.id(), other.user(), work, CREATED);
        String token = switch (role) {
            case "owner" -> owner.access();
            case "other" -> other.access();
            default -> null;
        };

        Page result = page(read(1, cursor(row.getId(), 0), token));

        assertEquals(List.of(work), ids(result));
        Map<String, Object> card = result.items().get(0);
        assertEquals("😀".repeat(280), card.get("excerpt"));
        assertEquals(Map.of("likeCount", 1, "bookmarkCount", 1, "commentCount", 0), card.get("stats"));
        assertEquals(Map.of("liked", "other".equals(role), "bookmarked", "other".equals(role),
                "isOwner", "owner".equals(role)), card.get("viewer"));
        assertEquals(BASE + "/media/public/assets/" + cover + "/thumbnail", object(card.get("cover")).get("url"));
        assertEquals(owner.user(), object(card.get("author")).get("id"));
        assertEquals(row, feeds.get(row.getId()));
        assertCursor(result, 1);
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

        assertError(read(2, null, token), 401, code);

        assertEquals(0, sessionCount());
    }

    @Test
    void concurrentSessions() throws Exception {
        Set<String> expected = Set.copyOf(seed(account(), "PUBLISHED", 7));
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return page(read(2, null, null));
            });
            var second = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return page(read(2, null, null));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Page one = first.get(15, TimeUnit.SECONDS);
            Page two = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(one.session(), two.session());
            assertEquals(2, sessionCount());
            for (Page result : List.of(one, two)) {
                List<FeedItemRow> saved = entries(result.session());
                assertEquals(expected, Set.copyOf(entryIds(saved)));
                assertEquals(7, saved.size());
                assertEquals(entryIds(saved.subList(0, 2)), ids(result));
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
                VALUES (?, ?, ?, 'private-password-hash', '作品流作者', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "feed_" + user.replace("-", "").substring(0, 12), CREATED);
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

    private List<String> seed(Account owner, String state, int count) {
        var ids = new ArrayList<String>();
        var arguments = new ArrayList<Object[]>();
        for (int i = 0; i < count; i++) {
            String id = Values.id();
            ids.add(id);
            arguments.add(new Object[]{id, owner.user(), state, CREATED, CREATED + 1000,
                    "PUBLISHED".equals(state) ? CREATED + 1000 : null});
        }
        jdbc.batchUpdate("""
                INSERT INTO works (id, owner_id, kind, status, version, title, body,
                  created_at, updated_at, published_at)
                VALUES (?, ?, 'TEXT', ?, 7, '作品流作品', '正文', ?, ?, ?)
                """, arguments);
        return ids;
    }

    private FeedRow snapshot(List<String> ids) {
        // 明确控制快照顺序，验证失效条目和补查；随机创建路径由其他 HTTP 测试覆盖。
        var row = new FeedRow();
        row.setId(Values.id());
        row.setCutoff(Values.now());
        row.setExpiresAt(Values.now() + TTL);
        feeds.insert(row);
        for (int i = 0; i < ids.size(); i++) feeds.item(row.getId(), i, ids.get(i));
        return row;
    }

    private String cover(Account owner) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, thumbnail_key, size_bytes, created_at)
                VALUES (?, ?, 'WORK_COVER', 'IMAGE', 'READY', 'image/jpeg', ?, ?, 1024, ?)
                """, id, owner.user(), "original/" + id, "thumbnails/" + id + ".jpg", CREATED);
        return id;
    }

    private int sessionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM feed_sessions", Integer.class);
    }

    private List<FeedItemRow> entries(String session) {
        return feeds.page(session, -1, 2001);
    }

    private List<String> entryIds(List<FeedItemRow> rows) {
        return rows.stream().map(FeedItemRow::getWorkId).toList();
    }

    private List<String> ids(Page page) {
        return page.items().stream().map(item -> (String) item.get("id")).toList();
    }

    private Map<String, Object> claims(String session, long position) {
        return Values.map("route", "/feed", "filter", "", "session", session, "position", position);
    }

    private String cursor(String session, long position) {
        return crypto.ticket("cursor", claims(session, position), 86_400_000);
    }

    private void assertCursor(Page result, long position) {
        assertTrue(result.more());
        Map<String, Object> claims = crypto.open("cursor", result.cursor(), "INVALID_CURSOR");
        assertEquals("/feed", claims.get("route"));
        assertEquals("", claims.get("filter"));
        assertEquals(result.session(), claims.get("session"));
        assertEquals(position, ((Number) claims.get("position")).longValue());
    }

    private void assertLifetime(Page result, long before) {
        FeedRow row = feeds.get(result.session());
        long after = Values.now();
        assertTrue(row.getCutoff() >= before && row.getCutoff() <= after);
        assertTrue(row.getExpiresAt() >= before + TTL && row.getExpiresAt() <= after + TTL);
        assertEquals(Values.time(row.getExpiresAt()), result.expiresAt());
    }

    private MockHttpServletResponse read(Integer limit, String cursor, String token) throws Exception {
        var request = get(PATH);
        if (limit != null) request.param("limit", limit.toString());
        if (cursor != null) request.param("cursor", cursor);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return perform(request);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "feed-" + Values.id()))
                .andReturn().getResponse();
    }

    private Page page(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("feed-"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        Map<String, Object> data = object(body.get("data"));
        assertEquals(Set.of("items", "nextCursor", "hasMore", "feedSession"), data.keySet());
        List<?> raw = assertInstanceOf(List.class, data.get("items"));
        List<Map<String, Object>> items = raw.stream().map(this::object).toList();
        for (Map<String, Object> card : items) {
            assertEquals(CARD_FIELDS, card.keySet());
            assertEquals("PUBLISHED", card.get("status"));
            assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount", "createdAt"),
                    object(card.get("author")).keySet());
            assertEquals(Set.of("liked", "bookmarked", "isOwner"), object(card.get("viewer")).keySet());
        }
        boolean more = assertInstanceOf(Boolean.class, data.get("hasMore"));
        String cursor = (String) data.get("nextCursor");
        if (more) assertNotNull(cursor);
        else assertNull(cursor);
        Map<String, Object> session = object(data.get("feedSession"));
        assertEquals(Set.of("id", "expiresAt"), session.keySet());
        String sessionId = assertInstanceOf(String.class, session.get("id"));
        String expiresAt = assertInstanceOf(String.class, session.get("expiresAt"));
        assertFalse(sessionId.isBlank());
        return new Page(items, cursor, more, sessionId, expiresAt);
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
    private record Page(List<Map<String, Object>> items, String cursor, boolean more, String session, String expiresAt) {}
}
