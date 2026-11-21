package com.silver.backend;

import com.silver.backend.business.RequestContext;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.dto.Ack;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@Import(AuthLogoutTest.ProbeController.class)
class AuthLogoutTest extends BackendTestSupport {
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String PROBE = "/api/v1/test/logout-user";
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
    @MockitoSpyBean private TransactionHelper tx;

    private final String clientIp = "203.0.113." + NEXT_IP.getAndIncrement();
    private final List<String> fixtureUsers = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String user : fixtureUsers) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void logsOut() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);

        accepted(logout(login.refresh()));

        SessionRow after = users.session(before.getId());
        assertNotNull(after);
        assertTrue(after.isRevoked());
        assertEquals(before.getUserId(), after.getUserId());
        assertEquals(before.getRefreshHash(), after.getRefreshHash());
        assertEquals(before.getCreatedAt(), after.getCreatedAt());
        assertEquals(before.getExpiresAt(), after.getExpiresAt());
        assertEquals(1, sessionCount(before.getUserId()));
    }

    @Test
    void clearsCookies() throws Exception {
        AuthService.LoginResult login = login();

        MockHttpServletResponse response = logout(login.refresh());

        accepted(response);
        assertEquals(2, response.getHeaders(HttpHeaders.SET_COOKIE).size());
        assertCleared(response.getCookie("htw_refresh"), "/api/v1/auth");
        assertCleared(response.getCookie("htw_media"), "/api/v1/media/private/");
        assertNull(response.getCookie("htw_csrf_context"));
    }

    @Test
    void blocksAccess() throws Exception {
        AuthService.LoginResult login = login();
        String access = login.data().accessToken();
        assertEquals(200, protectedRequest(access).getStatus());

        accepted(logout(login.refresh()));

        // JWT 的签名和自身有效期仍然通过，但数据库会话已撤销。
        Viewer viewer = crypto.verifyJwt(access);
        ApiException error = assertThrows(ApiException.class, () -> auth.checkSession(viewer));
        assertEquals("SESSION_REVOKED", error.code);
        assertError(protectedRequest(access), 401, "SESSION_REVOKED");
    }

    @Test
    void blocksRefresh() throws Exception {
        AuthService.LoginResult login = login();

        accepted(logout(login.refresh()));

        var request = authorized(post("/api/v1/auth/refresh"))
                .cookie(new Cookie("htw_refresh", login.refresh()));
        assertError(perform(request), 401, "REFRESH_INVALID");
    }

    @Test
    void invalidatesMedia() throws Exception {
        AuthService.LoginResult login = login();
        Map<String, Object> payload = crypto.open("media-read", login.media(), "TOKEN_INVALID");
        Viewer viewer = new Viewer((String) payload.get("user"), (String) payload.get("session"));
        auth.checkSession(viewer);

        accepted(logout(login.refresh()));

        // 私有媒体 HTTP 读取在后续步骤实现，此处验证其依赖的会话有效性规则。
        assertNotNull(crypto.open("media-read", login.media(), "TOKEN_INVALID"));
        ApiException error = assertThrows(ApiException.class, () -> auth.checkSession(viewer));
        assertEquals(401, error.status);
        assertEquals("SESSION_REVOKED", error.code);
    }

    @Test
    void otherSessions() throws Exception {
        AuthService.LoginResult first = login();
        SessionRow a = session(first);
        AuthService.LoginResult second = auth.login(new LoginInput(
                users.user(a.getUserId()).getEmail(), PASSWORD));
        SessionRow b = session(second);

        accepted(logout(first.refresh()));

        assertTrue(users.session(a.getId()).isRevoked());
        assertEquals(b, users.session(b.getId()));
        assertEquals(200, protectedRequest(second.data().accessToken()).getStatus());
        AuthService.LoginResult renewed = auth.refresh(second.refresh());
        assertEquals(b.getId(), crypto.verifyJwt(renewed.data().accessToken()).sessionId());
        assertEquals(2, sessionCount(a.getUserId()));
    }

    @Test
    void noCookie() throws Exception {
        accepted(logout(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid-token", "0000000000000000000000000000000000000000000", "abc.def.ghi"})
    void invalidToken(String token) throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);

        MockHttpServletResponse response = logout(token);

        accepted(response);
        assertCleared(response.getCookie("htw_refresh"), "/api/v1/auth");
        assertCleared(response.getCookie("htw_media"), "/api/v1/media/private/");
        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void missingSession() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        jdbc.update("DELETE FROM sessions WHERE id = ?", id);

        accepted(logout(login.refresh()));

        assertNull(users.session(id));
    }

    @Test
    void revokedSession() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", id);
        SessionRow before = users.session(id);

        accepted(logout(login.refresh()));

        assertEquals(before, users.session(id));
    }

    @Test
    void expiredSession() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        jdbc.update("UPDATE sessions SET expires_at = ? WHERE id = ?", Values.now() - 1_000, id);
        SessionRow before = users.session(id);

        accepted(logout(login.refresh()));

        SessionRow after = users.session(id);
        assertTrue(after.isRevoked());
        assertEquals(before.getExpiresAt(), after.getExpiresAt());
        assertEquals(before.getRefreshHash(), after.getRefreshHash());
    }

    @Test
    void repeats() throws Exception {
        AuthService.LoginResult login = login();
        String id = session(login).getId();
        accepted(logout(login.refresh()));
        SessionRow before = users.session(id);

        accepted(logout(login.refresh()));

        assertEquals(before, users.session(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "mismatched", "wrongOrigin"})
    void needsCsrf(String kind) throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        String context = crypto.randomToken();
        var request = post(LOGOUT).cookie(new Cookie("htw_refresh", login.refresh()))
                .header("Origin", "wrongOrigin".equals(kind) ? "https://other.example.test" : ORIGIN);
        if (!"missing".equals(kind)) {
            request.cookie(new Cookie("htw_csrf_context",
                            "mismatched".equals(kind) ? crypto.randomToken() : context))
                    .header("X-CSRF-Token", auth.csrf(context).csrfToken());
        }

        assertError(perform(request), 403, "CSRF_INVALID");

        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void usesCookie() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        var request = authorized(post(LOGOUT)).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("refresh", login.refresh())));

        accepted(perform(request));

        assertEquals(before, users.session(before.getId()));
    }

    @Test
    void expiredAccess() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow session = session(login);
        String expired = crypto.jwt(session.getUserId(), session.getId(), -1);
        assertThrows(ApiException.class, () -> crypto.verifyJwt(expired));

        // 退出请求使用刷新 Cookie，不需要携带已过期的 Authorization 头。
        accepted(logout(login.refresh()));

        assertTrue(users.session(session.getId()).isRevoked());
    }

    @Test
    void concurrentLogout() throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Ack>>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return auth.logout(login.refresh());
                }));
            }
            start.countDown();
            for (Future<Ack> future : futures) assertTrue(future.get(10, TimeUnit.SECONDS).success());
            SessionRow after = users.session(before.getId());
            assertTrue(after.isRevoked());
            assertEquals(before.getRefreshHash(), after.getRefreshHash());
            assertEquals(1, sessionCount(before.getUserId()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rotate", "delete"})
    void changedBeforeLock(String change) throws Exception {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);
        var logoutThread = new AtomicReference<Thread>();
        var hintRead = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        // 仅控制线程交错；Mapper、锁和事务都执行真实逻辑。
        doAnswer(invocation -> {
            if (Thread.currentThread() == logoutThread.get()) {
                hintRead.countDown();
                assertTrue(proceed.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(tx).write(anyCollection(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Ack> logout = pool.submit(() -> {
                logoutThread.set(Thread.currentThread());
                return auth.logout(login.refresh());
            });
            assertTrue(hintRead.await(5, TimeUnit.SECONDS));
            String latestHash = null;
            if ("rotate".equals(change)) {
                AuthService.LoginResult renewed = auth.refresh(login.refresh());
                latestHash = crypto.digest(renewed.refresh());
            } else {
                jdbc.update("DELETE FROM sessions WHERE id = ?", before.getId());
            }
            proceed.countDown();
            assertTrue(logout.get(10, TimeUnit.SECONDS).success());
            SessionRow after = users.session(before.getId());
            if ("rotate".equals(change)) {
                assertTrue(after.isRevoked());
                assertEquals(latestHash, after.getRefreshHash());
                assertEquals(before.getExpiresAt(), after.getExpiresAt());
            } else {
                assertNull(after);
            }
        } finally {
            proceed.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rollsBack() {
        AuthService.LoginResult login = login();
        SessionRow before = session(login);

        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            assertTrue(auth.logout(login.refresh()).success());
            throw new IllegalStateException("Rollback test transaction");
        }));

        assertEquals(before, users.session(before.getId()));
        auth.checkSession(crypto.verifyJwt(login.data().accessToken()));
        assertNotNull(auth.refresh(login.refresh()));
    }

    private AuthService.LoginResult login() {
        String id = Values.id();
        String email = id + "@example.test";
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, ?, '测试用户', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, email, "logout_" + id.replace("-", "").substring(0, 12),
                passwords.encode(PASSWORD), Values.now());
        fixtureUsers.add(id);
        return auth.login(new LoginInput(email, PASSWORD));
    }

    private SessionRow session(AuthService.LoginResult login) {
        SessionRow row = users.session(crypto.verifyJwt(login.data().accessToken()).sessionId());
        assertNotNull(row);
        return row;
    }

    private int sessionCount(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE user_id = ?", Integer.class, user);
    }

    private MockHttpServletResponse logout(String token) throws Exception {
        var request = authorized(post(LOGOUT));
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

    private MockHttpServletResponse protectedRequest(String token) throws Exception {
        return mvc.perform(get(PROBE).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private void accepted(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Map.of("success", true), body.get("data"));
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertNotNull(response.getHeader("X-Request-Id"));
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
        assertEquals(code, json.readMap(response.getContentAsString()).get("code"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    @TestComponent
    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        String user(HttpServletRequest request) {
            return RequestContext.viewer(request).userId();
        }
    }
}
