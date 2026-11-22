package com.silver.backend;

import com.silver.backend.business.dto.ProfilePatch;
import com.silver.backend.business.dto.Settings;
import com.silver.backend.business.dto.SettingsPatch;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
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
class SettingTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/me/settings";

    @Autowired private MockMvc mvc;
    @Autowired private ProfileService profiles;
    @Autowired private AuthMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private Requests requests;
    @Autowired private TransactionHelper tx;

    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String user : userIds) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @ParameterizedTest
    @CsvSource({"SYSTEM,SYSTEM,true", "DARK,REDUCED,false"})
    void reads(String theme, String motion, boolean autoPlay) throws Exception {
        Fixture owner = account(theme, motion, autoPlay);
        UserRow before = users.user(owner.user());

        assertSettings(read(owner), before);

        assertEquals(before, users.user(owner.user()));
        assertEquals(owner.session(), users.session(owner.session().getId()));
    }

    @Test
    void updatesAll() throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        UserRow before = users.user(owner.user());

        // Bearer 业务写接口不需要认证 Cookie 或 CSRF。
        Map<String, Object> result = data(update(owner,
                Map.of("theme", "LIGHT", "motion", "REDUCED", "autoPlayMedia", false)));

        before.setTheme("LIGHT");
        before.setMotion("REDUCED");
        before.setAutoPlayMedia(false);
        assertEquals(before, users.user(owner.user()), "设置更新不能覆盖资料、密码或邮箱");
        assertSettings(result, before);
        assertSettings(read(owner), before);
        assertEquals(owner.session(), users.session(owner.session().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "LIGHT", "DARK"})
    void themeOnly(String theme) throws Exception {
        Fixture owner = account("DARK", "REDUCED", false);
        UserRow before = users.user(owner.user());

        Map<String, Object> result = data(update(owner, Map.of("theme", theme)));

        before.setTheme(theme);
        assertEquals(before, users.user(owner.user()));
        assertSettings(result, before);
        assertSettings(read(owner), before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "REDUCED"})
    void motionOnly(String motion) throws Exception {
        Fixture owner = account("LIGHT", "REDUCED", false);
        UserRow before = users.user(owner.user());

        Map<String, Object> result = data(update(owner, Map.of("motion", motion)));

        before.setMotion(motion);
        assertEquals(before, users.user(owner.user()));
        assertSettings(result, before);
        assertSettings(read(owner), before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void autoPlayOnly(boolean enabled) throws Exception {
        Fixture owner = account("DARK", "REDUCED", !enabled);
        UserRow before = users.user(owner.user());

        Map<String, Object> result = data(update(owner, Map.of("autoPlayMedia", enabled)));

        before.setAutoPlayMedia(enabled);
        assertEquals(before, users.user(owner.user()), "显式 false 不能被当成字段缺席");
        assertSettings(result, before);
        assertSettings(read(owner), before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "empty", "unknown", "nullTheme", "nullMotion", "nullAuto", "badTheme", "badMotion",
            "paddedTheme", "lowerTheme", "themeType", "motionType", "autoString", "autoNumber", "mixed"
    })
    void badInput(String kind) throws Exception {
        Fixture owner = account("DARK", "REDUCED", true);
        UserRow before = users.user(owner.user());
        var body = new LinkedHashMap<String, Object>();
        String field = switch (kind) {
            case "empty" -> "";
            case "unknown" -> "email";
            case "nullMotion", "badMotion", "motionType", "mixed" -> "motion";
            case "nullAuto", "autoString", "autoNumber" -> "autoPlayMedia";
            default -> "theme";
        };
        switch (kind) {
            case "empty" -> { }
            case "unknown" -> body.put(field, "other@example.test");
            case "nullTheme", "nullMotion", "nullAuto" -> body.put(field, null);
            case "badTheme" -> body.put(field, "BLUE");
            case "badMotion" -> body.put(field, "FULL");
            case "paddedTheme" -> body.put(field, " DARK ");
            case "lowerTheme" -> body.put(field, "dark");
            case "themeType" -> body.put(field, 123);
            case "motionType" -> body.put(field, List.of("SYSTEM"));
            case "autoString" -> body.put(field, "false");
            case "autoNumber" -> body.put(field, 0);
            case "mixed" -> {
                body.put("theme", "LIGHT");
                body.put(field, "INVALID");
            }
            default -> throw new IllegalArgumentException(kind);
        }

        MockHttpServletResponse response = update(owner, body);

        assertError(response, 422, "VALIDATION_FAILED");
        List<?> details = (List<?>) json.readMap(response.getContentAsString()).get("details");
        assertFalse(details.isEmpty());
        assertEquals(field, ((Map<?, ?>) details.get(0)).get("field"));
        assertEquals(before, users.user(owner.user()));
        assertSettings(read(owner), before);
    }

    @Test
    void onlyCurrent() throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        Fixture other = account("DARK", "REDUCED", false);
        UserRow otherBefore = users.user(other.user());
        assertSettings(data(perform(authorized(get(PATH).param("userId", other.user()), owner.access()))),
                users.user(owner.user()));

        Map<String, Object> result = data(perform(request(owner.access(), Map.of("theme", "LIGHT"))
                .param("userId", other.user())));

        assertEquals("LIGHT", users.user(owner.user()).getTheme());
        assertSettings(result, users.user(owner.user()));
        assertEquals(otherBefore, users.user(other.user()));
        assertEquals(other.session(), users.session(other.session().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "patch"})
    void needsBearer(String operation) throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        UserRow before = users.user(owner.user());
        var request = "read".equals(operation) ? get(PATH) : request(null, Map.of("theme", "LIGHT"));

        assertError(perform(request), 401, "AUTH_REQUIRED");

        assertEquals(before, users.user(owner.user()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "expired", "revoked"})
    void badBearer(String kind) throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        UserRow before = users.user(owner.user());
        String token = owner.access();
        String code;
        switch (kind) {
            case "invalid" -> {
                token = "invalid-token";
                code = "TOKEN_INVALID";
            }
            case "expired" -> {
                token = crypto.jwt(owner.user(), owner.session().getId(), -1);
                code = "ACCESS_TOKEN_EXPIRED";
            }
            case "revoked" -> {
                jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", owner.session().getId());
                code = "SESSION_REVOKED";
            }
            default -> throw new IllegalArgumentException(kind);
        }

        assertError(perform(authorized(get(PATH), token)), 401, code);
        assertError(perform(request(token, Map.of("theme", "LIGHT"))), 401, code);

        assertEquals(before, users.user(owner.user()));
    }

    @Test
    void rollsBack() throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        UserRow before = users.user(owner.user());

        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            apply(owner, Map.of("theme", "LIGHT", "motion", "REDUCED", "autoPlayMedia", false));
            throw new IllegalStateException("Rollback test transaction");
        }));

        assertEquals(before, users.user(owner.user()));
        assertSettings(read(owner), before);
        assertEquals(owner.session(), users.session(owner.session().getId()));
    }

    @Test
    void concurrentSettings() throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        UserRow before = users.user(owner.user());

        together(List.of(
                () -> apply(owner, Map.of("theme", "LIGHT")),
                () -> apply(owner, Map.of("motion", "REDUCED")),
                () -> apply(owner, Map.of("autoPlayMedia", false))));

        before.setTheme("LIGHT");
        before.setMotion("REDUCED");
        before.setAutoPlayMedia(false);
        assertEquals(before, users.user(owner.user()), "不同设置字段并发更新后应全部保留");
        assertSettings(read(owner), before);
    }

    @Test
    void concurrentProfile() throws Exception {
        Fixture owner = account("SYSTEM", "SYSTEM", true);
        UserRow before = users.user(owner.user());

        together(List.of(
                () -> apply(owner, Map.of("theme", "LIGHT", "motion", "REDUCED", "autoPlayMedia", false)),
                () -> profiles.patch(viewer(owner), requests.patch("ProfilePatch",
                        Map.of("displayName", "并发昵称", "bio", "并发简介"), ProfilePatch.class))));

        before.setTheme("LIGHT");
        before.setMotion("REDUCED");
        before.setAutoPlayMedia(false);
        before.setDisplayName("并发昵称");
        before.setBio("并发简介");
        assertEquals(before, users.user(owner.user()), "资料和设置必须共用用户锁，避免整行更新覆盖彼此");
        assertSettings(read(owner), before);
        assertEquals(owner.session(), users.session(owner.session().getId()));
    }

    @Test
    void guestService() {
        ApiException read = assertThrows(ApiException.class, () -> profiles.settings(Viewer.guest()));
        var input = requests.patch("SettingsPatch", Map.of("theme", "LIGHT"), SettingsPatch.class);
        ApiException patch = assertThrows(ApiException.class,
                () -> profiles.settingsPatch(Viewer.guest(), input));

        for (ApiException failure : List.of(read, patch)) {
            assertEquals(401, failure.status);
            assertEquals("AUTH_REQUIRED", failure.code);
        }
    }

    private Fixture account(String theme, String motion, boolean autoPlay) {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '原昵称', '原简介', TRUE, ?, ?, ?, ?)
                """, user, user + "@example.test", "setting_" + user.replace("-", "").substring(0, 12),
                theme, motion, autoPlay, Values.now());
        userIds.add(user);
        var row = new SessionRow();
        row.setId(Values.id());
        row.setUserId(user);
        row.setRefreshHash(crypto.digest(crypto.randomToken()));
        row.setCreatedAt(Values.now());
        row.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(row);
        return new Fixture(user, row, crypto.jwt(user, row.getId(), 900));
    }

    private Viewer viewer(Fixture fixture) {
        return new Viewer(fixture.user(), fixture.session().getId());
    }

    private Settings apply(Fixture owner, Map<String, Object> body) {
        return profiles.settingsPatch(viewer(owner), requests.patch("SettingsPatch", body, SettingsPatch.class));
    }

    private void together(List<Runnable> actions) throws Exception {
        var pool = Executors.newFixedThreadPool(actions.size());
        var ready = new CountDownLatch(actions.size());
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try {
            for (Runnable action : actions) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    action.run();
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Map<String, Object> read(Fixture owner) throws Exception {
        return data(perform(authorized(get(PATH), owner.access())));
    }

    private MockHttpServletResponse update(Fixture owner, Map<String, Object> body) throws Exception {
        return perform(request(owner.access(), body));
    }

    private MockHttpServletRequestBuilder request(String token, Map<String, Object> body) {
        return authorized(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(json.write(body)), token);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "settings-" + Values.id()))
                .andReturn().getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("settings-"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        assertInstanceOf(Map.class, body.get("data"));
        return (Map<String, Object>) body.get("data");
    }

    private void assertSettings(Map<String, Object> data, UserRow user) {
        assertEquals(Map.of("theme", user.getTheme(), "motion", user.getMotion(),
                "autoPlayMedia", user.isAutoPlayMedia()), data);
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("settings-"));
        assertFalse(body.containsKey("data"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private record Fixture(String user, SessionRow session, String access) {}
}
