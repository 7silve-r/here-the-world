package com.silver.backend;

import com.silver.backend.business.feed.Pages;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PagesTest {
    private static final String ROUTE = "/me/works";
    private static final String FILTER = "user-1:DRAFT";
    private static final String ID = "work-9007199254740993";
    private static final String SESSION = "feed-session-1";
    private static final long TIME = 1_700_000_000_123L;
    private static final long DAY = 86_400_000L;

    private Pages pages;
    private Crypto crypto;
    private JsonCodec json;

    @BeforeEach
    void setUp() {
        json = new JsonCodec();
        ReflectionTestUtils.setField(json, "mapper", JsonMapper.builder().build());
        crypto = new Crypto("0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(crypto, "json", json);
        pages = new Pages();
        ReflectionTestUtils.setField(pages, "crypto", crypto);
    }

    @Test
    @DisplayName("未传每页数量时默认返回 24")
    void defaultLimit() {
        assertEquals(24, pages.limit(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24, 50})
    @DisplayName("接受范围内的每页数量，包括两端边界")
    void validLimit(int limit) {
        assertEquals(limit, pages.limit(limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 51, Integer.MAX_VALUE})
    @DisplayName("拒绝超出 1 到 50 的每页数量")
    void badLimit(int limit) {
        ApiException error = assertThrows(ApiException.class, () -> pages.limit(limit));
        assertEquals(400, error.status);
        assertEquals("BAD_REQUEST", error.code);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("首次请求根据排序方向返回对应的初始查询边界")
    void initialPosition(boolean ascending) {
        Pages.Position position = pages.position(null, ROUTE, FILTER, ascending);

        assertEquals(ascending ? -1L : Long.MAX_VALUE, position.time());
        assertEquals(ascending ? "" : "~", position.id());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2_147_483_648L, TIME, Long.MAX_VALUE})
    @DisplayName("游标无损恢复排序时间与字符串 ID，支持不同大小的 JSON 数字")
    void roundTrip(long time) {
        String cursor = pages.cursor(ROUTE, FILTER, time, ID);

        assertEquals(new Pages.Position(time, ID), pages.position(cursor, ROUTE, FILTER, false));
        assertEquals(new Pages.Position(time, ID), pages.position(cursor, ROUTE, FILTER, true));
    }

    @Test
    @DisplayName("普通游标携带列表、筛选条件、位置和 24 小时有效期")
    void cursorClaims() {
        long before = Values.now();

        String cursor = pages.cursor(ROUTE, FILTER, TIME, ID);

        long after = Values.now();
        Map<String, Object> claims = pages.open(cursor, ROUTE, FILTER);
        assertEquals(Set.of("route", "filter", "time", "id", "exp"), claims.keySet());
        assertEquals(ROUTE, claims.get("route"));
        assertEquals(FILTER, claims.get("filter"));
        assertEquals(TIME, ((Number) claims.get("time")).longValue());
        assertEquals(ID, claims.get("id"));
        long expiry = ((Number) claims.get("exp")).longValue();
        assertTrue(expiry >= before + DAY && expiry <= after + DAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/me/bookmarks", "/users/user-1/works"})
    @DisplayName("同一个游标不能用于其他列表")
    void routeMismatch(String route) {
        String cursor = pages.cursor(ROUTE, FILTER, TIME, ID);

        invalid(() -> pages.open(cursor, route, FILTER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user-1:PUBLISHED", "user-2:DRAFT"})
    @DisplayName("同一个游标不能更换筛选状态或用户范围")
    void filterMismatch(String filter) {
        String cursor = pages.cursor(ROUTE, FILTER, TIME, ID);

        invalid(() -> pages.position(cursor, ROUTE, filter, false));
    }

    @Test
    @DisplayName("普通列表拒绝已过期的有效签名游标，不等待真实时间")
    void expiredCursor() {
        String cursor = crypto.ticket("cursor", ordinaryClaims(), -60_000);

        invalid(() -> pages.position(cursor, ROUTE, FILTER, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "abc", "a.", ".b", "a.b.c", "%%.%%"})
    @DisplayName("空串及错误格式游标返回 INVALID_CURSOR")
    void badCursor(String cursor) {
        invalid(() -> pages.position(cursor, ROUTE, FILTER, false));
    }

    @Test
    @DisplayName("即使签名有效，也拒绝超过长度上限的游标")
    void oversizedCursor() {
        String filter = "x".repeat(4096);
        String cursor = pages.cursor(ROUTE, filter, TIME, ID);
        assertTrue(cursor.length() > 4096);

        ApiException error = invalid(() -> pages.open(cursor, ROUTE, filter));

        assertEquals("游标过长", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "id"})
    @DisplayName("修改分页位置而保留原校验值会被拒绝")
    void tamperedPosition(String field) {
        String cursor = pages.cursor(ROUTE, FILTER, TIME, ID);
        Object value = "time".equals(field) ? TIME + 1 : "another-work";
        String changed = tamper(cursor, field, value);

        invalid(() -> pages.position(changed, ROUTE, FILTER, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {ROUTE, "/feed"})
    @DisplayName("普通列表与作品流都不能使用其他用途签发的票据")
    void wrongPurpose(String route) {
        Map<String, Object> claims = "/feed".equals(route) ? feedClaims() : ordinaryClaims();
        String cursor = crypto.ticket("upload", claims, 60_000);

        invalid(() -> pages.open(cursor, route, "/feed".equals(route) ? "" : FILTER));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 23, 1999})
    @DisplayName("作品流游标以数字保存快照位置，能够供后续查询读取")
    void feedRoundTrip(long position) {
        long before = Values.now();

        String cursor = feedCursor(position);

        long after = Values.now();
        Map<String, Object> claims = pages.open(cursor, "/feed", "");
        assertEquals(Set.of("route", "filter", "session", "position", "exp"), claims.keySet());
        assertEquals("/feed", claims.get("route"));
        assertEquals("", claims.get("filter"));
        assertEquals(SESSION, claims.get("session"));
        assertInstanceOf(Number.class, claims.get("position"));
        assertEquals(position, ((Number) claims.get("position")).longValue());
        long expiry = ((Number) claims.get("exp")).longValue();
        assertTrue(expiry >= before + DAY && expiry <= after + DAY);
    }

    @Test
    @DisplayName("作品流允许解析旧的有效签名游标，留给业务检查快照会话期限")
    void expiredFeed() {
        String cursor = crypto.ticket("cursor", feedClaims(), -60_000);

        Map<String, Object> claims = pages.open(cursor, "/feed", "");

        assertEquals(SESSION, claims.get("session"));
        assertEquals(23L, ((Number) claims.get("position")).longValue());
        assertTrue(((Number) claims.get("exp")).longValue() < Values.now());
    }

    @Test
    @DisplayName("作品流跳过票据过期检查时仍然拒绝被篡改的快照标识")
    void tamperedFeed() {
        String cursor = crypto.ticket("cursor", feedClaims(), -60_000);
        String changed = tamper(cursor, "session", "another-feed-session");

        invalid(() -> pages.open(changed, "/feed", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/me/works", "/me/bookmarks"})
    @DisplayName("作品流游标不能用于普通列表")
    void feedScope(String route) {
        String cursor = feedCursor(23);

        invalid(() -> pages.open(cursor, route, ""));
    }

    @Test
    @DisplayName("作品流游标仍然绑定原来的筛选条件")
    void feedFilter() {
        String cursor = feedCursor(23);

        invalid(() -> pages.open(cursor, "/feed", "PUBLISHED"));
    }

    @Test
    @DisplayName("普通列表游标不能当作作品流游标使用")
    void ordinaryAsFeed() {
        String cursor = pages.cursor(ROUTE, FILTER, TIME, ID);

        invalid(() -> pages.open(cursor, "/feed", ""));
    }

    private Map<String, Object> ordinaryClaims() {
        return Values.map("route", ROUTE, "filter", FILTER, "time", TIME, "id", ID);
    }

    private Map<String, Object> feedClaims() {
        return Values.map("route", "/feed", "filter", "", "session", SESSION, "position", 23L);
    }

    private String feedCursor(long position) {
        return pages.feed(SESSION, position);
    }

    private ApiException invalid(Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(400, error.status);
        assertEquals("INVALID_CURSOR", error.code);
        return error;
    }

    private String tamper(String cursor, String field, Object value) {
        String[] parts = cursor.split("\\.", -1);
        Map<String, Object> claims = json.readMap(new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        claims.put(field, value);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.write(claims).getBytes(StandardCharsets.UTF_8));
        return body + "." + parts[1];
    }
}
