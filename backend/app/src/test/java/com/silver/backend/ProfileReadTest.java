package com.silver.backend;

import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.user.ProfileService;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
@TestPropertySource(properties = "htw.public-api-base-url=https://api.example.test/api/v1")
class ProfileReadTest extends BackendTestSupport {
    private static final String ME = "/api/v1/me";
    private static final String USERS = "/api/v1/users/";
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;
    private static final String CREATED_AT = "2023-11-14T22:13:20Z";
    private static final Set<String> PUBLIC_FIELDS = Set.of(
            "id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount", "createdAt");

    @Autowired private MockMvc mvc;
    @Autowired private ProfileService profiles;
    @Autowired private AuthMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;

    private final List<String> userIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String work : workIds) jdbc.update("DELETE FROM works WHERE id = ?", work);
        for (String asset : assetIds) jdbc.update("DELETE FROM assets WHERE id = ?", asset);
        for (String user : userIds) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "self", "other"})
    void publicProfile(String viewer) throws Exception {
        String owner = user();
        work(owner, "PUBLISHED");
        work(owner, "PUBLISHED");
        work(owner, "DRAFT");
        work(owner, "PROCESSING");
        work(owner, "FAILED");
        String other = user();
        work(other, "PUBLISHED");
        String token = switch (viewer) {
            case "self" -> identity(owner).access();
            case "other" -> identity(other).access();
            default -> null;
        };

        Map<String, Object> result = data(read(USERS + owner, token));

        assertEquals(PUBLIC_FIELDS, result.keySet());
        assertPublic(result, owner, 2, null);
    }

    @Test
    void me() throws Exception {
        String owner = user();
        Identity login = identity(owner);
        UserRow before = users.user(owner);
        SessionRow session = users.session(login.session().getId());
        work(owner, "PUBLISHED");
        work(owner, "DRAFT");
        String other = user();
        work(other, "PUBLISHED");

        // GET 只携带 Bearer，无需先获取 CSRF，也不依赖 Cookie。
        Map<String, Object> result = data(perform(get(ME).param("userId", other)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.access())));

        var fields = new HashSet<>(PUBLIC_FIELDS);
        fields.addAll(Set.of("email", "emailVerified"));
        assertEquals(fields, result.keySet());
        assertPublic(result, owner, 1, null);
        assertEquals(before.getEmail(), result.get("email"));
        assertEquals(true, result.get("emailVerified"));
        assertEquals(before, users.user(owner));
        assertEquals(session, users.session(session.getId()), "读取资料不应轮换令牌或延长会话");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingUser(boolean authenticated) throws Exception {
        String token = authenticated ? identity(user()).access() : null;

        assertError(read(USERS + Values.id(), token), 404, "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "refresh", "media", "both"})
    void needsBearer(String cookies) throws Exception {
        String owner = user();
        Identity login = identity(owner);
        var request = get(ME);
        if ("refresh".equals(cookies) || "both".equals(cookies)) {
            request.cookie(new Cookie("htw_refresh", login.refresh()));
        }
        if ("media".equals(cookies) || "both".equals(cookies)) {
            request.cookie(new Cookie("htw_media", login.media()));
        }

        assertError(perform(request), 401, "AUTH_REQUIRED");

        assertEquals(login.session(), users.session(login.session().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "scheme", "expiredJwt", "revoked", "expiredSession", "missingSession", "wrongUser"})
    void badBearer(String kind) throws Exception {
        String owner = user();
        Identity login = identity(owner);
        String session = login.session().getId();
        String header = "Bearer " + login.access();
        String code = "SESSION_REVOKED";
        switch (kind) {
            case "invalid" -> {
                header = "Bearer invalid-token";
                code = "TOKEN_INVALID";
            }
            case "scheme" -> {
                header = "Token " + login.access();
                code = "TOKEN_INVALID";
            }
            case "expiredJwt" -> {
                header = "Bearer " + crypto.jwt(owner, session, -1);
                code = "ACCESS_TOKEN_EXPIRED";
            }
            case "revoked" -> jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", session);
            case "expiredSession" -> jdbc.update("UPDATE sessions SET expires_at = ? WHERE id = ?",
                    Values.now() - 1_000, session);
            case "missingSession" -> jdbc.update("DELETE FROM sessions WHERE id = ?", session);
            case "wrongUser" -> header = "Bearer " + crypto.jwt(Values.id(), session, 900);
            default -> throw new IllegalArgumentException(kind);
        }
        UserRow before = users.user(owner);

        // 公开接口允许完全匿名访问；主动携带无效凭证时仍应明确返回 401。
        for (String path : List.of(ME, USERS + owner)) {
            assertError(perform(get(path).header(HttpHeaders.AUTHORIZATION, header)), 401, code);
        }
        assertEquals(PUBLIC_FIELDS, data(read(USERS + owner, null)).keySet());
        assertEquals(before, users.user(owner));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "CREATED", "PROCESSING", "FAILED"})
    void avatar(String status) throws Exception {
        String owner = user();
        Identity login = identity(owner);
        String asset = avatar(owner, status);
        String url = "READY".equals(status) ? BASE + "/media/public/assets/" + asset + "/file" : null;

        Map<String, Object> publicUser = data(read(USERS + owner, null));
        Map<String, Object> me = data(read(ME, login.access()));

        assertPublic(publicUser, owner, 0, url);
        assertPublic(me, owner, 0, url);
    }

    @Test
    void missingAvatar() throws Exception {
        String owner = user();
        Identity login = identity(owner);
        jdbc.update("UPDATE users SET avatar_asset_id = ? WHERE id = ?", Values.id(), owner);

        assertPublic(data(read(USERS + owner, null)), owner, 0, null);
        assertPublic(data(read(ME, login.access())), owner, 0, null);
    }

    @Test
    void viewerIsolation() throws Exception {
        String first = user();
        String second = user();
        Identity a = identity(first);
        Identity b = identity(second);

        Map<String, Object> firstMe = data(read(ME, a.access()));
        Map<String, Object> secondMe = data(read(ME, b.access()));

        assertEquals(first, firstMe.get("id"));
        assertEquals(users.user(first).getEmail(), firstMe.get("email"));
        assertEquals(second, secondMe.get("id"));
        assertEquals(users.user(second).getEmail(), secondMe.get("email"));
        assertError(read(ME, null), 401, "AUTH_REQUIRED");
    }

    @Test
    void guestService() {
        ApiException failure = assertThrows(ApiException.class, () -> profiles.me(Viewer.guest()));
        assertEquals(401, failure.status);
        assertEquals("AUTH_REQUIRED", failure.code);
    }

    private String user() {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '资料测试用户', '公开的个人简介',
                  TRUE, 'DARK', 'REDUCED', TRUE, ?)
                """, id, id + "@example.test", "profile_" + id.replace("-", "").substring(0, 12), CREATED);
        userIds.add(id);
        return id;
    }

    private Identity identity(String owner) {
        // 直接构造有效会话夹具，HTTP 请求仍经过真实 JWT 校验和数据库会话检查。
        String refresh = crypto.randomToken();
        var row = new SessionRow();
        row.setId(Values.id());
        row.setUserId(owner);
        row.setRefreshHash(crypto.digest(refresh));
        row.setCreatedAt(Values.now());
        row.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(row);
        String media = crypto.ticket("media-read", Map.of("user", owner, "session", row.getId()), 86_400_000);
        return new Identity(crypto.jwt(owner, row.getId(), 900), refresh, media, row);
    }

    private void work(String owner, String status) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO works (id, owner_id, kind, status, version, title, body,
                  created_at, updated_at, published_at)
                VALUES (?, ?, 'TEXT', ?, 1, '资料统计作品', '', ?, ?, ?)
                """, id, owner, status, CREATED, CREATED, "PUBLISHED".equals(status) ? CREATED : null);
        workIds.add(id);
    }

    private String avatar(String owner, String status) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, size_bytes, created_at)
                VALUES (?, ?, 'AVATAR', 'IMAGE', ?, 'image/jpeg', ?, 1024, ?)
                """, id, owner, status, "avatars/" + id + ".jpg", CREATED);
        assetIds.add(id);
        jdbc.update("UPDATE users SET avatar_asset_id = ? WHERE id = ?", id, owner);
        return id;
    }

    private MockHttpServletResponse read(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return perform(request);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "profile-read-" + Values.id()))
                .andReturn().getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(response.getContentType())));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("profile-read-"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        assertInstanceOf(Map.class, body.get("data"));
        return (Map<String, Object>) body.get("data");
    }

    private void assertPublic(Map<String, Object> data, String owner, long count, String avatar) {
        assertEquals(owner, data.get("id"));
        assertEquals(users.user(owner).getUsername(), data.get("username"));
        assertEquals("资料测试用户", data.get("displayName"));
        assertEquals("公开的个人简介", data.get("bio"));
        assertTrue(data.containsKey("avatarUrl"));
        assertEquals(avatar, data.get("avatarUrl"));
        assertEquals(count, ((Number) data.get("publishedWorkCount")).longValue());
        assertEquals(CREATED_AT, data.get("createdAt"));
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("code", "message", "requestId", "details"), body.keySet());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("profile-read-"));
        assertInstanceOf(String.class, body.get("message"));
        assertEquals(List.of(), body.get("details"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private record Identity(String access, String refresh, String media, SessionRow session) {}
}
