package com.silver.backend;

import com.silver.backend.business.RequestContext;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.dto.ChangePasswordInput;
import com.silver.backend.business.dto.LoginInput;
import com.silver.backend.business.dto.ResetPasswordInput;
import com.silver.backend.business.persistence.ResetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TransactionHelper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@Import(PasswordChangeTest.ProbeController.class)
class PasswordChangeTest extends BackendTestSupport {
    private static final String CHANGE = "/api/v1/auth/change-password";
    private static final String PROBE = "/api/v1/test/change-user";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "Original-password-123";
    private static final String NEW_PASSWORD = "Replacement-password-456";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private AuthMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private TransactionHelper tx;

    private final String clientIp = "198.51.100." + NEXT_IP.getAndIncrement();
    private final List<String> fixtureUsers = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM rate_limits WHERE id = ?", rateKey());
        for (String user : fixtureUsers) {
            jdbc.update("DELETE FROM password_resets WHERE user_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void changes() throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult first = login(user, PASSWORD);
        AuthService.LoginResult second = login(user, PASSWORD);
        String firstReset = resetToken(user);
        String secondReset = resetToken(user);
        UserRow before = users.user(user);
        assertEquals(200, protectedRequest(first.data().accessToken()).getStatus());

        MockHttpServletResponse response = change(first, PASSWORD, NEW_PASSWORD);

        succeeded(response);
        UserRow after = users.user(user);
        assertNotEquals(before.getPasswordHash(), after.getPasswordHash());
        assertNotEquals(NEW_PASSWORD, after.getPasswordHash());
        assertTrue(passwords.matches(NEW_PASSWORD, after.getPasswordHash()));
        assertFalse(passwords.matches(PASSWORD, after.getPasswordHash()));
        before.setPasswordHash(after.getPasswordHash());
        assertEquals(before, after, "修改密码不应覆盖资料和偏好");
        assertEquals(2, state(user).sessions().size());
        assertTrue(state(user).sessions().stream().allMatch(SessionRow::isRevoked));
        assertTrue(users.reset(crypto.digest(firstReset)).isConsumed());
        assertTrue(users.reset(crypto.digest(secondReset)).isConsumed());
        assertEquals(2, response.getHeaders(HttpHeaders.SET_COOKIE).size());
        assertCleared(response.getCookie("htw_refresh"), "/api/v1/auth");
        assertCleared(response.getCookie("htw_media"), "/api/v1/media/private/");
        assertNull(response.getCookie("htw_csrf_context"));
        assertError(protectedRequest(first.data().accessToken()), 401, "SESSION_REVOKED");
        assertError(protectedRequest(second.data().accessToken()), 401, "SESSION_REVOKED");
        assertError(perform(csrf(post("/api/v1/auth/refresh"))
                .cookie(new Cookie("htw_refresh", second.refresh()))), 401, "REFRESH_INVALID");

        // 媒体签名本身尚未到期，但其绑定的会话已经失效；媒体读取接口在后续步骤实现。
        Map<String, Object> media = crypto.open("media-read", first.media(), "TOKEN_INVALID");
        ApiException revoked = assertThrows(ApiException.class, () -> auth.checkSession(
                new Viewer((String) media.get("user"), (String) media.get("session"))));
        assertEquals("SESSION_REVOKED", revoked.code);
        ApiException oldPassword = assertThrows(ApiException.class, () -> login(user, PASSWORD));
        assertEquals("INVALID_CREDENTIALS", oldPassword.code);
        AuthService.LoginResult renewed = login(user, NEW_PASSWORD);
        assertEquals(200, protectedRequest(renewed.data().accessToken()).getStatus());
    }

    @Test
    void onlyOwner() throws Exception {
        String owner = user(PASSWORD);
        AuthService.LoginResult ownLogin = login(owner, PASSWORD);
        resetToken(owner);
        String other = user(PASSWORD);
        AuthService.LoginResult otherLogin = login(other, PASSWORD);
        resetToken(other);
        State otherBefore = state(other);

        // 业务身份由 Bearer 决定，不能被另一个账号的刷新 Cookie 替换。
        MockHttpServletResponse response = perform(request(ownLogin.data().accessToken(),
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
                .cookie(new Cookie("htw_refresh", otherLogin.refresh())));

        succeeded(response);
        assertTrue(passwords.matches(NEW_PASSWORD, users.user(owner).getPasswordHash()));
        assertEquals(otherBefore, state(other));
        assertEquals(200, protectedRequest(otherLogin.data().accessToken()).getStatus());
        assertNotNull(auth.refresh(otherLogin.refresh()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Wrong-password-789", " Original-password-123 ", "Replacement-password-456"})
    void wrongPassword(String current) throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        State before = state(user);

        assertError(change(login, current, NEW_PASSWORD), 401, "INVALID_CREDENTIALS");

        assertEquals(before, state(user));
        assertEquals(200, protectedRequest(login.data().accessToken()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missingCurrent", "missingNew", "nullCurrent", "nullNew", "emptyCurrent",
            "longCurrent", "shortNew", "longNew", "currentType", "newType", "extraField"
    })
    void badInput(String kind) throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        State before = state(user);
        var body = new LinkedHashMap<String, Object>();
        body.put("currentPassword", PASSWORD);
        body.put("newPassword", NEW_PASSWORD);
        String field = switch (kind) {
            case "missingCurrent", "nullCurrent", "emptyCurrent", "longCurrent", "currentType" ->
                    "currentPassword";
            case "extraField" -> "repassword";
            default -> "newPassword";
        };
        switch (kind) {
            case "missingCurrent", "missingNew" -> body.remove(field);
            case "nullCurrent", "nullNew" -> body.put(field, null);
            case "emptyCurrent" -> body.put(field, "");
            case "longCurrent", "longNew" -> body.put(field, "x".repeat(129));
            case "shortNew" -> body.put(field, "x".repeat(9));
            case "currentType" -> body.put(field, 12345);
            case "newType" -> body.put(field, List.of(NEW_PASSWORD));
            case "extraField" -> body.put(field, NEW_PASSWORD);
            default -> throw new IllegalArgumentException(kind);
        }

        MockHttpServletResponse response = perform(request(login.data().accessToken(), body));

        assertError(response, 422, "VALIDATION_FAILED");
        List<?> details = (List<?>) json.readMap(response.getContentAsString()).get("details");
        assertFalse(details.isEmpty());
        assertEquals(field, ((Map<?, ?>) details.get(0)).get("field"));
        assertEquals(before, state(user));
    }

    @Test
    void passwordSpaces() throws Exception {
        String current = " " + PASSWORD + " ";
        String replacement = " " + NEW_PASSWORD + " ";
        String user = user(current);
        AuthService.LoginResult login = login(user, current);

        succeeded(change(login, current, replacement));

        String hash = users.user(user).getPasswordHash();
        assertTrue(passwords.matches(replacement, hash));
        assertFalse(passwords.matches(NEW_PASSWORD, hash));
        assertNotNull(login(user, replacement));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 128})
    void passwordLength(int length) throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        String replacement = "🔒".repeat(length);

        succeeded(change(login, PASSWORD, replacement));

        assertTrue(passwords.matches(replacement, users.user(user).getPasswordHash()));
        assertNotNull(login(user, replacement));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "refresh", "media", "both"})
    void needsBearer(String cookies) throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        State before = state(user);
        var request = request(null, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));
        if ("refresh".equals(cookies) || "both".equals(cookies)) {
            request.cookie(new Cookie("htw_refresh", login.refresh()));
        }
        if ("media".equals(cookies) || "both".equals(cookies)) {
            request.cookie(new Cookie("htw_media", login.media()));
        }

        assertError(perform(request), 401, "AUTH_REQUIRED");

        assertEquals(before, state(user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "expiredJwt", "revoked", "expiredSession", "missingSession", "wrongUser"})
    void badBearer(String kind) throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        Viewer viewer = crypto.verifyJwt(login.data().accessToken());
        String token = login.data().accessToken();
        String code = "SESSION_REVOKED";
        switch (kind) {
            case "invalid" -> {
                token = "invalid-token";
                code = "TOKEN_INVALID";
            }
            case "expiredJwt" -> {
                token = crypto.jwt(user, viewer.sessionId(), -1);
                code = "ACCESS_TOKEN_EXPIRED";
            }
            case "revoked" -> jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", viewer.sessionId());
            case "expiredSession" -> jdbc.update("UPDATE sessions SET expires_at = ? WHERE id = ?",
                    Values.now() - 1_000, viewer.sessionId());
            case "missingSession" -> jdbc.update("DELETE FROM sessions WHERE id = ?", viewer.sessionId());
            case "wrongUser" -> token = crypto.jwt(Values.id(), viewer.sessionId(), 900);
            default -> throw new IllegalArgumentException(kind);
        }
        State before = state(user);

        assertError(perform(request(token, Map.of("currentPassword", PASSWORD,
                "newPassword", NEW_PASSWORD))), 401, code);

        assertEquals(before, state(user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingToken", "missingContext", "mismatch", "origin", "missingOrigin"})
    void needsCsrf(String kind) throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        State before = state(user);
        String context = crypto.randomToken();
        var request = post(CHANGE)
                .header("Authorization", "Bearer " + login.data().accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)));
        if (!"missingOrigin".equals(kind)) {
            request.header("Origin", "origin".equals(kind) ? "https://other.example.test" : ORIGIN);
        }
        if (!"missingContext".equals(kind)) request.cookie(new Cookie("htw_csrf_context", context));
        if (!"missingToken".equals(kind)) {
            String signedContext = "mismatch".equals(kind) ? crypto.randomToken() : context;
            request.header("X-CSRF-Token", auth.csrf(signedContext).csrfToken());
        }

        assertError(perform(request), 403, "CSRF_INVALID");

        assertEquals(before, state(user));
    }

    @Test
    void sharedLimit() throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        State before = state(user);
        // 直接构造时间窗边界，避免发送 180 次请求或等待一分钟。
        jdbc.update("INSERT INTO rate_limits(id, hits, expires_at) VALUES (?, 179, ?)",
                rateKey(), Values.now() + 60_000);
        assertEquals(200, perform(csrf(post("/api/v1/auth/logout"))).getStatus());

        MockHttpServletResponse response = change(login, PASSWORD, NEW_PASSWORD);

        assertError(response, 429, "RATE_LIMITED");
        assertEquals("60", response.getHeader("Retry-After"));
        assertEquals(before, state(user));
    }

    @Test
    void revokesReset() throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        String token = resetToken(user);

        succeeded(change(login, PASSWORD, NEW_PASSWORD));

        ApiException failure = assertThrows(ApiException.class,
                () -> auth.reset(new ResetPasswordInput(token, "Another-password-789")));
        assertEquals(422, failure.status);
        assertEquals("RESET_TOKEN_INVALID", failure.code);
        assertTrue(passwords.matches(NEW_PASSWORD, users.user(user).getPasswordHash()));
    }

    @Test
    void rollsBack() throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        State before = state(user);
        Viewer viewer = crypto.verifyJwt(login.data().accessToken());

        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            auth.change(viewer, new ChangePasswordInput(PASSWORD, NEW_PASSWORD));
            throw new IllegalStateException("Rollback test transaction");
        }));

        assertEquals(before, state(user));
        assertEquals(200, protectedRequest(login.data().accessToken()).getStatus());
        succeeded(change(login, PASSWORD, NEW_PASSWORD));
    }

    @Test
    void concurrentChanges() throws Exception {
        String user = user(PASSWORD);
        AuthService.LoginResult login = login(user, PASSWORD);
        resetToken(user);
        Viewer viewer = crypto.verifyJwt(login.data().accessToken());
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Attempt>>();
        try {
            for (String replacement : List.of(NEW_PASSWORD + "-A", NEW_PASSWORD + "-B")) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        auth.change(viewer, new ChangePasswordInput(PASSWORD, replacement));
                        return new Attempt(replacement, null);
                    } catch (ApiException failure) {
                        return new Attempt(replacement, failure.code);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var attempts = new ArrayList<Attempt>();
            for (Future<Attempt> future : futures) attempts.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, attempts.stream().filter(attempt -> attempt.code() == null).count());
            assertEquals(1, attempts.stream().filter(attempt -> "INVALID_CREDENTIALS".equals(attempt.code())).count());
            Attempt winner = attempts.stream().filter(attempt -> attempt.code() == null)
                    .findFirst().orElseThrow();
            assertTrue(passwords.matches(winner.password(), users.user(user).getPasswordHash()));
            assertTrue(state(user).sessions().stream().allMatch(SessionRow::isRevoked));
            assertTrue(state(user).resets().stream().allMatch(ResetRow::isConsumed));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void guestService() {
        ApiException failure = assertThrows(ApiException.class, () -> auth.change(
                Viewer.guest(), new ChangePasswordInput(PASSWORD, NEW_PASSWORD)));
        assertEquals(401, failure.status);
        assertEquals("AUTH_REQUIRED", failure.code);
    }

    private String user(String password) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, ?, '改密测试', '保留简介', TRUE, 'DARK', 'REDUCED', FALSE, ?)
                """, id, id + "@example.test", "change_" + id.replace("-", "").substring(0, 12),
                passwords.encode(password), Values.now());
        fixtureUsers.add(id);
        return id;
    }

    private AuthService.LoginResult login(String user, String password) {
        return auth.login(new LoginInput(users.user(user).getEmail(), password));
    }

    private String resetToken(String user) {
        String token = crypto.randomToken();
        var row = new ResetRow();
        row.setTokenHash(crypto.digest(token));
        row.setUserId(user);
        row.setExpiresAt(Values.now() + 1_800_000);
        users.insertReset(row);
        return token;
    }

    private State state(String user) {
        List<SessionRow> sessions = jdbc.queryForList(
                "SELECT id FROM sessions WHERE user_id = ? ORDER BY id", String.class, user)
                .stream().map(users::session).toList();
        List<ResetRow> resets = jdbc.queryForList(
                "SELECT token_hash FROM password_resets WHERE user_id = ? ORDER BY token_hash", String.class, user)
                .stream().map(users::reset).toList();
        return new State(users.user(user), sessions, resets);
    }

    private MockHttpServletResponse change(AuthService.LoginResult login, String current, String replacement)
            throws Exception {
        return perform(request(login.data().accessToken(),
                Map.of("currentPassword", current, "newPassword", replacement)));
    }

    private MockHttpServletRequestBuilder request(String token, Map<String, Object> body) {
        var request = csrf(post(CHANGE)).contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        String context = crypto.randomToken();
        return request.header("Origin", ORIGIN).cookie(new Cookie("htw_csrf_context", context))
                .header("X-CSRF-Token", auth.csrf(context).csrfToken());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(incoming -> {
            incoming.setRemoteAddr(clientIp);
            return incoming;
        })).andReturn().getResponse();
    }

    private MockHttpServletResponse protectedRequest(String token) throws Exception {
        return mvc.perform(get(PROBE).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private String rateKey() {
        return crypto.keyedDigest("limit", "auth-source:" + clientIp);
    }

    private void succeeded(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(Map.of("success", true), body.get("data"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertNotNull(body.get("requestId"));
    }

    private void assertCleared(Cookie cookie, String path) {
        assertNotNull(cookie);
        assertEquals("", cookie.getValue());
        assertEquals(0, cookie.getMaxAge());
        assertEquals(path, cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertFalse(body.containsKey("data"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private record State(UserRow user, List<SessionRow> sessions, List<ResetRow> resets) {}
    private record Attempt(String password, String code) {}

    @TestComponent
    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        String user(HttpServletRequest request) {
            return RequestContext.viewer(request).userId();
        }
    }
}
