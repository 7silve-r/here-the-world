package com.silver.backend;

import com.silver.backend.business.dto.Me;
import com.silver.backend.business.dto.ProfilePatch;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.system.Requests;
import com.silver.backend.business.user.ProfileService;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TransactionHelper;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

@AutoConfigureMockMvc
@TestPropertySource(properties = "htw.public-api-base-url=https://api.example.test/api/v1")
class ProfilePatchTest extends BackendTestSupport {
    private static final String ME = "/api/v1/me";
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;
    private static final Set<String> ME_FIELDS = Set.of(
            "id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount",
            "createdAt", "email", "emailVerified");

    @Autowired private MockMvc mvc;
    @Autowired private ProfileService profiles;
    @Autowired private AuthMapper users;
    @Autowired private AssetMapper assets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private Requests requests;
    @Autowired private TransactionHelper tx;

    private final List<String> userIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String asset : assetIds) jdbc.update("DELETE FROM assets WHERE id = ?", asset);
        for (String user : userIds) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void updatesAll() throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        AssetRow oldAvatar = assets.asset(owner.avatar());
        String next = asset(owner.user(), "AVATAR", "IMAGE", "READY");
        AssetRow nextAvatar = assets.asset(next);

        // Bearer 业务写接口不需要认证 Cookie 或 CSRF；空白按业务规则 strip。
        Map<String, Object> result = data(update(owner, Map.of(
                "displayName", " \t新昵称\u2003 ", "bio", " \n新的简介\t ", "avatarAssetId", next)));

        before.setDisplayName("新昵称");
        before.setBio("新的简介");
        before.setAvatarAssetId(next);
        assertEquals(before, users.user(owner.user()));
        assertMe(result, before, next);
        assertEquals(owner.session(), users.session(owner.session().getId()));
        assertEquals(0, assets.avatarReferences(owner.avatar()));
        assertEquals(1, assets.avatarReferences(next));
        assertEquals(oldAvatar, assets.asset(owner.avatar()), "替换头像只解除旧引用，不直接删除资源");
        assertEquals(nextAvatar, assets.asset(next));

        Map<String, Object> publicUser = data(perform(get("/api/v1/users/" + owner.user())));
        assertEquals("新昵称", publicUser.get("displayName"));
        assertEquals("新的简介", publicUser.get("bio"));
        assertEquals(url(next), publicUser.get("avatarUrl"));
        assertFalse(publicUser.containsKey("email"));
    }

    @Test
    void displayOnly() throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());

        Map<String, Object> result = data(update(owner, Map.of("displayName", "  新名字  ")));

        before.setDisplayName("新名字");
        assertEquals(before, users.user(owner.user()), "缺席的 bio 和 avatarAssetId 必须保持原值");
        assertMe(result, before, owner.avatar());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t "})
    void clearBio(String bio) throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());

        Map<String, Object> result = data(update(owner, Map.of("bio", bio)));

        before.setBio("");
        assertEquals(before, users.user(owner.user()));
        assertMe(result, before, owner.avatar());
    }

    @Test
    void avatarOnly() throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        String next = asset(owner.user(), "AVATAR", "IMAGE", "READY");

        Map<String, Object> result = data(update(owner, Map.of("avatarAssetId", next)));

        before.setAvatarAssetId(next);
        assertEquals(before, users.user(owner.user()));
        assertMe(result, before, next);
    }

    @Test
    void clearAvatar() throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        AssetRow avatar = assets.asset(owner.avatar());

        Map<String, Object> result = data(update(owner, Collections.singletonMap("avatarAssetId", null)));

        before.setAvatarAssetId(null);
        assertEquals(before, users.user(owner.user()));
        assertMe(result, before, null);
        assertEquals(0, assets.avatarReferences(owner.avatar()));
        assertEquals(avatar, assets.asset(owner.avatar()));
        assertNull(data(perform(get("/api/v1/users/" + owner.user()))).get("avatarUrl"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "empty", "unknown", "nullDisplay", "nullBio", "displayType", "bioType", "avatarType",
            "emptyDisplay", "blankDisplay", "longDisplay", "longBio", "emptyAvatar", "longAvatar"
    })
    void badInput(String kind) throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        var body = new LinkedHashMap<String, Object>();
        String field = switch (kind) {
            case "empty" -> "";
            case "unknown" -> "email";
            case "nullBio", "bioType", "longBio" -> "bio";
            case "avatarType", "emptyAvatar", "longAvatar" -> "avatarAssetId";
            default -> "displayName";
        };
        switch (kind) {
            case "empty" -> { }
            case "unknown" -> body.put(field, "other@example.test");
            case "nullDisplay", "nullBio" -> body.put(field, null);
            case "displayType" -> body.put(field, 123);
            case "bioType" -> body.put(field, List.of("简介"));
            case "avatarType" -> body.put(field, true);
            case "emptyDisplay", "emptyAvatar" -> body.put(field, "");
            case "blankDisplay" -> body.put(field, " \t\n\u2003 ");
            case "longDisplay" -> body.put(field, "x".repeat(41));
            case "longBio" -> body.put(field, "x".repeat(501));
            case "longAvatar" -> body.put(field, "x".repeat(65));
            default -> throw new IllegalArgumentException(kind);
        }

        MockHttpServletResponse response = update(owner, body);

        assertError(response, 422, "VALIDATION_FAILED");
        List<?> details = (List<?>) json.readMap(response.getContentAsString()).get("details");
        assertFalse(details.isEmpty());
        assertEquals(field, ((Map<?, ?>) details.get(0)).get("field"));
        assertEquals(before, users.user(owner.user()));
        assertEquals(1, assets.avatarReferences(owner.avatar()));
    }

    @Test
    void unicodeLength() throws Exception {
        Fixture owner = profile();
        String display = "🌍".repeat(40);
        String bio = "🌍".repeat(500);

        Map<String, Object> result = data(update(owner, Map.of("displayName", display, "bio", bio)));

        assertEquals(display, result.get("displayName"));
        assertEquals(bio, result.get("bio"));
        assertEquals(display, users.user(owner.user()).getDisplayName());
        assertEquals(bio, users.user(owner.user()).getBio());
        assertEquals(owner.avatar(), users.user(owner.user()).getAvatarAssetId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "foreign", "purpose", "mediaType", "CREATED", "PROCESSING", "FAILED"})
    void badAvatar(String kind) throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        String avatar;
        String code;
        switch (kind) {
            case "missing" -> {
                avatar = Values.id();
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "foreign" -> {
                avatar = asset(user(), "AVATAR", "IMAGE", "READY");
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "purpose" -> {
                avatar = asset(owner.user(), "WORK_MEDIA", "IMAGE", "READY");
                code = "ASSET_TYPE_MISMATCH";
            }
            case "mediaType" -> {
                avatar = asset(owner.user(), "AVATAR", "VIDEO", "READY");
                code = "ASSET_TYPE_MISMATCH";
            }
            default -> {
                avatar = asset(owner.user(), "AVATAR", "IMAGE", kind);
                code = "ASSET_NOT_READY";
            }
        }
        AssetRow assetBefore = assets.asset(avatar);

        assertError(update(owner, Map.of("displayName", "不应保存的昵称", "bio", "不应保存的简介",
                "avatarAssetId", avatar)), 422, code);

        assertEquals(before, users.user(owner.user()), "头像校验失败时，其他已提交字段也不能写入");
        assertEquals(assetBefore, assets.asset(avatar));
        assertEquals(1, assets.avatarReferences(owner.avatar()));
        assertEquals(0, assets.avatarReferences(avatar));
    }

    @Test
    void onlyCurrent() throws Exception {
        Fixture owner = profile();
        Fixture other = profile();
        UserRow otherBefore = users.user(other.user());

        Map<String, Object> result = data(perform(request(owner.access(), Map.of("displayName", "本人新名字"))
                .param("userId", other.user())));

        assertEquals(owner.user(), result.get("id"));
        assertEquals("本人新名字", users.user(owner.user()).getDisplayName());
        assertEquals(otherBefore, users.user(other.user()));
        assertEquals(other.session(), users.session(other.session().getId()));
    }

    @Test
    void needsLogin() throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());

        assertError(perform(request(null, Map.of("displayName", "不能匿名修改"))), 401, "AUTH_REQUIRED");

        assertEquals(before, users.user(owner.user()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "revoked"})
    void badBearer(String kind) throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        String token = owner.access();
        String code;
        if ("invalid".equals(kind)) {
            token = "invalid-token";
            code = "TOKEN_INVALID";
        } else {
            jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", owner.session().getId());
            code = "SESSION_REVOKED";
        }

        assertError(perform(request(token, Map.of("displayName", "不能修改"))), 401, code);

        assertEquals(before, users.user(owner.user()));
    }

    @Test
    void rollsBack() {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        String next = asset(owner.user(), "AVATAR", "IMAGE", "READY");

        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            profiles.patch(viewer(owner), requests.patch("ProfilePatch", Map.of(
                    "displayName", "回滚昵称", "bio", "回滚简介", "avatarAssetId", next), ProfilePatch.class));
            throw new IllegalStateException("Rollback test transaction");
        }));

        assertEquals(before, users.user(owner.user()));
        assertEquals(1, assets.avatarReferences(owner.avatar()));
        assertEquals(0, assets.avatarReferences(next));
        assertEquals(owner.session(), users.session(owner.session().getId()));
    }

    @Test
    void concurrentFields() throws Exception {
        Fixture owner = profile();
        UserRow before = users.user(owner.user());
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Me>>();
        try {
            for (Map<String, Object> body : List.<Map<String, Object>>of(
                    Map.of("displayName", "并发昵称"), Map.of("bio", "并发简介"))) {
                futures.add(pool.submit(() -> {
                    var input = requests.patch("ProfilePatch", body, ProfilePatch.class);
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return profiles.patch(viewer(owner), input);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Me> future : futures) assertEquals(owner.user(), future.get(15, TimeUnit.SECONDS).id());

            before.setDisplayName("并发昵称");
            before.setBio("并发简介");
            assertEquals(before, users.user(owner.user()), "锁内重新读取用户，避免不同字段的更新互相覆盖");
            assertEquals(owner.session(), users.session(owner.session().getId()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void guestService() {
        var input = requests.patch("ProfilePatch", Map.of("displayName", "访客"), ProfilePatch.class);

        ApiException failure = assertThrows(ApiException.class, () -> profiles.patch(Viewer.guest(), input));

        assertEquals(401, failure.status);
        assertEquals("AUTH_REQUIRED", failure.code);
    }

    private String user() {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '原昵称', '原简介',
                  TRUE, 'DARK', 'REDUCED', FALSE, ?)
                """, id, id + "@example.test", "patch_" + id.replace("-", "").substring(0, 12), CREATED);
        userIds.add(id);
        return id;
    }

    private Fixture profile() {
        String owner = user();
        String avatar = asset(owner, "AVATAR", "IMAGE", "READY");
        jdbc.update("UPDATE users SET avatar_asset_id = ? WHERE id = ?", avatar, owner);
        var row = new SessionRow();
        row.setId(Values.id());
        row.setUserId(owner);
        row.setRefreshHash(crypto.digest(crypto.randomToken()));
        row.setCreatedAt(Values.now());
        row.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(row);
        return new Fixture(owner, avatar, row, crypto.jwt(owner, row.getId(), 900));
    }

    private String asset(String owner, String purpose, String type, String status) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1024, ?)
                """, id, owner, purpose, type, status, "VIDEO".equals(type) ? "video/mp4" : "image/jpeg",
                "original/" + id, CREATED);
        assetIds.add(id);
        return id;
    }

    private Viewer viewer(Fixture fixture) {
        return new Viewer(fixture.user(), fixture.session().getId());
    }

    private MockHttpServletResponse update(Fixture owner, Map<String, Object> body) throws Exception {
        return perform(request(owner.access(), body));
    }

    private MockHttpServletRequestBuilder request(String token, Map<String, Object> body) {
        var request = patch(ME).contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "profile-patch-" + Values.id()))
                .andReturn().getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("profile-patch-"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        assertInstanceOf(Map.class, body.get("data"));
        return (Map<String, Object>) body.get("data");
    }

    private void assertMe(Map<String, Object> data, UserRow user, String avatar) {
        assertEquals(ME_FIELDS, data.keySet());
        assertEquals(user.getId(), data.get("id"));
        assertEquals(user.getUsername(), data.get("username"));
        assertEquals(user.getDisplayName(), data.get("displayName"));
        assertEquals(user.getBio(), data.get("bio"));
        assertEquals(avatar == null ? null : url(avatar), data.get("avatarUrl"));
        assertEquals(0, ((Number) data.get("publishedWorkCount")).longValue());
        assertEquals("2023-11-14T22:13:20Z", data.get("createdAt"));
        assertEquals(user.getEmail(), data.get("email"));
        assertEquals(true, data.get("emailVerified"));
    }

    private String url(String avatar) {
        return BASE + "/media/public/assets/" + avatar + "/file";
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("profile-patch-"));
        assertFalse(body.containsKey("data"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private record Fixture(String user, String avatar, SessionRow session, String access) {}
}
