package com.silver.backend;

import com.silver.backend.business.RequestContext;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.dto.LoginInput;
import com.silver.backend.business.persistence.SessionRow;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@Import(AuthRefreshTest.ProbeController.class)
class AuthRefreshTest extends BackendTestSupport {
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String PROBE = "/api/v1/test/refresh-user";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "Test-password-123";
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
        for (String user : fixtureUsers) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void rotates() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);

        MockHttpServletResponse response = refresh(login.refresh());

        Map<String, Object> body = data(response);
        Cookie replacement = response.getCookie("htw_refresh");
        assertNotNull(replacement);
        assertNotEquals(login.refresh(), replacement.getValue());
        assertTrue(replacement.getValue().matches("[A-Za-z0-9_-]{43}"));
        SessionRow after = users.session(before.getId());
        assertEquals(crypto.digest(replacement.getValue()), after.getRefreshHash());
        assertNotEquals(replacement.getValue(), after.getRefreshHash());
        assertNull(users.refresh(crypto.digest(login.refresh())));
        assertEquals(before.getId(), crypto.verifyJwt((String) body.get("accessToken")).sessionId());
        assertEquals(before.getCreatedAt(), after.getCreatedAt());
        assertEquals(before.getExpiresAt(), after.getExpiresAt());
        assertFalse(after.isRevoked());
        assertEquals(1, sessionCount(before.getUserId()));
    }

    @Test
    void cookies() throws Exception {
        AuthService.LoginResult login = login();

        MockHttpServletResponse response = refresh(login.refresh());

        data(response);
        assertEquals(2, response.getHeaders(HttpHeaders.SET_COOKIE).size());
        Cookie refresh = response.getCookie("htw_refresh");
        Cookie media = response.getCookie("htw_media");
        assertCookie(refresh, "/api/v1/auth");
        assertCookie(media, "/api/v1/media/private/");
        assertEquals(refresh.getMaxAge(), media.getMaxAge());
        String body = response.getContentAsString();
        assertFalse(body.contains(refresh.getValue()));
        assertFalse(body.contains(media.getValue()));
        assertFalse(body.contains("refreshHash"));
        assertTrue(refresh.getMaxAge() > 30 * 86_400 - 60);
        assertTrue(refresh.getMaxAge() <= 30 * 86_400);
    }

    @Test
    void keepsDeadline() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow initial = session(login);
        long now = Values.now();
        long created = now - 20L * 86_400_000;
        long expires = now + 10L * 86_400_000;
        jdbc.update("UPDATE sessions SET created_at = ?, expires_at = ? WHERE id = ?",
                created, expires, initial.getId());

        MockHttpServletResponse first = refresh(login.refresh());
        data(first);
        MockHttpServletResponse second = refresh(first.getCookie("htw_refresh").getValue());
        data(second);

        SessionRow current = users.session(initial.getId());
        assertEquals(created, current.getCreatedAt());
        assertEquals(expires, current.getExpiresAt());
        for (MockHttpServletResponse response : List.of(first, second)) {
            Cookie cookie = response.getCookie("htw_refresh");
            assertTrue(cookie.getMaxAge() > 10 * 86_400 - 60);
            assertTrue(cookie.getMaxAge() <= 10 * 86_400);
            Map<String, Object> media = crypto.open(
                    "media-read", response.getCookie("htw_media").getValue(), "TOKEN_INVALID");
            long mediaExpires = ((Number) media.get("exp")).longValue();
            assertTrue(mediaExpires > Values.now() + 10L * 86_400_000 - 60_000);
            assertTrue(mediaExpires <= Values.now() + 10L * 86_400_000);
        }
    }

    @Test
    void freshAccess() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow session = session(login);
        long before = Values.now() / 1000;

        Map<String, Object> data = data(refresh(login.refresh()));

        long after = Values.now() / 1000;
        assertEquals(Set.of("accessToken", "tokenType", "expiresIn", "user"), data.keySet());
        assertEquals("Bearer", data.get("tokenType"));
        assertEquals(900L, ((Number) data.get("expiresIn")).longValue());
        String access = (String) data.get("accessToken");
        assertEquals(new Viewer(session.getUserId(), session.getId()), crypto.verifyJwt(access));
        Map<String, Object> claims = json.readMap(new String(
                Base64.getUrlDecoder().decode(access.split("\\.")[1]), StandardCharsets.UTF_8));
        long issued = ((Number) claims.get("iat")).longValue();
        long expires = ((Number) claims.get("exp")).longValue();
        assertTrue(issued >= before && issued <= after);
        assertTrue(expires >= before + 900 && expires <= after + 900);
        // 同一秒签发的 JWT 可能完全相同，不依赖字符串一定变化来判断续签成功。
    }

    @Test
    void renewsMedia() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow session = session(login);

        MockHttpServletResponse response = refresh(login.refresh());

        data(response);
        Cookie media = response.getCookie("htw_media");
        assertNotNull(media);
        Map<String, Object> payload = crypto.open("media-read", media.getValue(), "TOKEN_INVALID");
        assertEquals(session.getUserId(), payload.get("user"));
        assertEquals(session.getId(), payload.get("session"));
        assertTrue(((Number) payload.get("exp")).longValue() > Values.now());
        assertThrows(ApiException.class, () -> crypto.open("csrf", media.getValue(), "TOKEN_INVALID"));
    }

    @Test
    void updatedProfile() throws Exception {
        AuthService.LoginResult login = login();
        String user = session(login).getUserId();
        jdbc.update("UPDATE users SET display_name = '更新后的昵称' WHERE id = ?", user);

        Map<?, ?> me = (Map<?, ?>) data(refresh(login.refresh())).get("user");

        assertEquals(user, me.get("id"));
        assertEquals("更新后的昵称", me.get("displayName"));
        assertEquals(users.user(user).getEmail(), me.get("email"));
        assertEquals(Boolean.TRUE, me.get("emailVerified"));
        assertFalse(me.containsKey("passwordHash"));
    }

    @Test
    void replaysFail() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        MockHttpServletResponse first = refresh(login.refresh());
        data(first);
        SessionRow current = users.session(id);

        assertError(refresh(login.refresh()), 401, "REFRESH_INVALID");

        assertEquals(current, users.session(id));
        data(refresh(first.getCookie("htw_refresh").getValue()));
        assertEquals(1, sessionCount(current.getUserId()));
    }

    @Test
    void noCookie() throws Exception {
        assertError(refresh(null), 401, "REFRESH_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-token", "0000000000000000000000000000000000000000000", "abc.def.ghi"})
    void badToken(String token) throws Exception {
        assertError(refresh(token), 401, "REFRESH_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "media", "session"})
    void wrongCredential(String kind) throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        String value = switch (kind) {
            case "access" -> login.data().accessToken();
            case "media" -> login.media();
            case "session" -> before.getId();
            default -> throw new IllegalArgumentException(kind);
        };

        assertError(refresh(value), 401, "REFRESH_INVALID");

        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void revoked() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", id);
        SessionRow before = users.session(id);

        assertError(refresh(login.refresh()), 401, "REFRESH_INVALID");

        assertEquals(before, users.session(id));
    }

    @Test
    void expired() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        jdbc.update("UPDATE sessions SET expires_at = ? WHERE id = ?", Values.now() - 1_000, id);
        SessionRow before = users.session(id);

        assertError(refresh(login.refresh()), 401, "REFRESH_INVALID");

        assertEquals(before, users.session(id));
    }

    @Test
    void missingSession() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        jdbc.update("DELETE FROM sessions WHERE id = ?", id);

        assertError(refresh(login.refresh()), 401, "REFRESH_INVALID");

        assertNull(users.session(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrongOrigin", "mismatched"})
    void needsCsrf(String kind) throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        String context = crypto.randomToken();
        var request = post(REFRESH).cookie(new Cookie("htw_refresh", login.refresh()))
                .header("Origin", "wrongOrigin".equals(kind) ? "https://other.example.test" : ORIGIN);
        if (!"missing".equals(kind)) {
            String cookie = "mismatched".equals(kind) ? crypto.randomToken() : context;
            request.cookie(new Cookie("htw_csrf_context", cookie))
                    .header("X-CSRF-Token", auth.csrf(context).csrfToken());
        }

        assertError(perform(request), 403, "CSRF_INVALID");

        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void usesCookie() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        var request = authorized(post(REFRESH)).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("refresh", login.refresh())));

        assertError(perform(request), 401, "REFRESH_INVALID");

        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void independentSession() throws Exception {
        AuthService.LoginResult first = login();
        SessionRow a = session(first);
        AuthService.LoginResult second = auth.login(new LoginInput(
                users.user(a.getUserId()).getEmail(), PASSWORD));
        SessionRow b = session(second);

        data(refresh(first.refresh()));

        assertNotEquals(a.getRefreshHash(), users.session(a.getId()).getRefreshHash());
        assertEquals(b, users.session(b.getId()));
        assertEquals(2, sessionCount(a.getUserId()));
        auth.checkSession(crypto.verifyJwt(second.data().accessToken()));
    }

    @Test
    void expiredAccess() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow session = session(login);
        String expired = crypto.jwt(session.getUserId(), session.getId(), -1);
        ApiException error = assertThrows(ApiException.class, () -> crypto.verifyJwt(expired));
        assertEquals("ACCESS_TOKEN_EXPIRED", error.code);

        // 刷新请求通过 Cookie 认证，不携带已经过期的 Authorization 头。
        Map<String, Object> data = data(refresh(login.refresh()));
        String access = (String) data.get("accessToken");

        MockHttpServletResponse response = protectedRequest(access);
        assertEquals(200, response.getStatus());
        assertEquals(session.getUserId(), response.getContentAsString());
    }

    @Test
    void revokedAfterRefresh() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow session = session(login);
        Map<String, Object> data = data(refresh(login.refresh()));
        String access = (String) data.get("accessToken");
        assertEquals(200, protectedRequest(access).getStatus());
        jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", session.getId());

        assertError(protectedRequest(access), 401, "SESSION_REVOKED");
    }

    @Test
    void concurrentRotate() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Attempt>>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        return new Attempt(auth.refresh(login.refresh()), 200, null);
                    } catch (ApiException failure) {
                        return new Attempt(null, failure.status, failure.code);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var attempts = new ArrayList<Attempt>();
            for (Future<Attempt> future : futures) attempts.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, attempts.stream().filter(attempt -> attempt.status() == 200).count());
            assertEquals(1, attempts.stream().filter(attempt ->
                    attempt.status() == 401 && "REFRESH_INVALID".equals(attempt.code())).count());
            AuthService.LoginResult winner = attempts.stream().filter(attempt -> attempt.status() == 200)
                    .findFirst().orElseThrow().result();
            assertEquals(crypto.digest(winner.refresh()), users.session(before.getId()).getRefreshHash());
            assertEquals(before.getExpiresAt(), users.session(before.getId()).getExpiresAt());
            assertEquals(1, sessionCount(before.getUserId()));
            data(refresh(winner.refresh()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rollsBack() {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        var discarded = new AtomicReference<AuthService.LoginResult>();

        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            discarded.set(auth.refresh(login.refresh()));
            throw new IllegalStateException("Rollback test transaction");
        }));

        assertEquals(before, users.session(before.getId()));
        ApiException invalid = assertThrows(ApiException.class, () -> auth.refresh(discarded.get().refresh()));
        assertEquals("REFRESH_INVALID", invalid.code);
        AuthService.LoginResult retried = auth.refresh(login.refresh());
        assertEquals(crypto.digest(retried.refresh()), users.session(before.getId()).getRefreshHash());
    }

    @Test
    void sourceLimit() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        for (int i = 0; i < 120; i++) {
            assertError(refresh("invalid-token"), 401, "REFRESH_INVALID");
        }

        MockHttpServletResponse response = refresh(login.refresh());

        assertError(response, 429, "RATE_LIMITED");
        assertNotNull(response.getHeader("Retry-After"));
        assertEquals(before, users.session(before.getId()));
    }

    private AuthService.LoginResult login() {
        String id = Values.id();
        String email = id + "@example.test";
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, ?, '测试用户', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, email, "refresh_" + id.replace("-", "").substring(0, 12),
                passwords.encode(PASSWORD), Values.now());
        fixtureUsers.add(id);
        return auth.login(new LoginInput(email, PASSWORD));
    }

    private SessionRow session(AuthService.LoginResult login) {
        SessionRow session = users.session(crypto.verifyJwt(login.data().accessToken()).sessionId());
        assertNotNull(session);
        return session;
    }

    private MockHttpServletResponse refresh(String token) throws Exception {
        MockHttpServletRequestBuilder request = authorized(post(REFRESH));
        if (token != null) request.cookie(new Cookie("htw_refresh", token));
        return perform(request);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
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

    private MockHttpServletResponse protectedRequest(String access) throws Exception {
        return mvc.perform(get(PROBE).header("Authorization", "Bearer " + access))
                .andReturn().getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return (Map<String, Object>) json.readMap(response.getContentAsString()).get("data");
    }

    private int sessionCount(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE user_id = ?", Integer.class, user);
    }

    private void assertCookie(Cookie cookie, String path) {
        assertNotNull(cookie);
        assertEquals(path, cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertEquals(code, json.readMap(response.getContentAsString()).get("code"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private record Attempt(AuthService.LoginResult result, int status, String code) {}

    @TestComponent
    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        String user(HttpServletRequest request) {
            return RequestContext.viewer(request).userId();
        }
    }
}
