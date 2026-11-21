package com.silver.backend;

import com.silver.backend.business.RequestContext;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@Import(AuthLoginTest.ProbeController.class)
class AuthLoginTest extends BackendTestSupport {
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PROBE = "/api/v1/test/login-user";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "Test-password-123";
    private static final long SESSION_MILLIS = 30L * 86_400_000;
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private AuthMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private PasswordEncoder passwords;

    private final String clientIp = "192.0.2." + NEXT_IP.getAndIncrement();
    private final List<String> fixtureUsers = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String user : fixtureUsers) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void logsIn() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        Map<String, Object> data = data(response);
        assertEquals(Set.of("accessToken", "tokenType", "expiresIn", "user"), data.keySet());
        assertEquals("Bearer", data.get("tokenType"));
        assertEquals(900L, ((Number) data.get("expiresIn")).longValue());
        assertFalse(((String) data.get("accessToken")).isBlank());
        Map<?, ?> me = (Map<?, ?>) data.get("user");
        assertEquals(user.getId(), me.get("id"));
        assertEquals(user.getEmail(), me.get("email"));
        assertEquals(Boolean.TRUE, me.get("emailVerified"));
        assertEquals("测试用户", me.get("displayName"));
        assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl",
                "publishedWorkCount", "createdAt", "email", "emailVerified"), me.keySet());
        assertEquals(1, sessionCount(user.getId()));
    }

    @Test
    void tokenClaims() throws Exception {
        UserRow user = user(true);
        long before = Values.now() / 1000;

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        String token = access(response);
        long after = Values.now() / 1000;
        Viewer viewer = crypto.verifyJwt(token);
        SessionRow session = users.session(viewer.sessionId());
        Map<String, Object> claims = json.readMap(new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
        assertEquals(user.getId(), viewer.userId());
        assertNotNull(session);
        assertEquals(user.getId(), session.getUserId());
        assertEquals("here-the-world", claims.get("iss"));
        assertEquals("htw-api", claims.get("aud"));
        assertEquals(user.getId(), claims.get("sub"));
        assertEquals(session.getId(), claims.get("sid"));
        long issued = ((Number) claims.get("iat")).longValue();
        long expires = ((Number) claims.get("exp")).longValue();
        assertTrue(issued >= before && issued <= after);
        assertTrue(expires >= before + 900 && expires <= after + 900);
        auth.checkSession(viewer);
    }

    @Test
    void sessionDigest() throws Exception {
        UserRow user = user(true);
        long before = Values.now();

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        SessionRow session = session(response);
        long after = Values.now();
        Cookie refresh = response.getCookie("htw_refresh");
        assertNotNull(refresh);
        assertTrue(refresh.getValue().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(crypto.digest(refresh.getValue()), session.getRefreshHash());
        assertNotEquals(refresh.getValue(), session.getRefreshHash());
        assertFalse(session.isRevoked());
        assertTrue(session.getCreatedAt() >= before && session.getCreatedAt() <= after);
        assertTrue(session.getExpiresAt() >= before + SESSION_MILLIS);
        assertTrue(session.getExpiresAt() <= after + SESSION_MILLIS);
    }

    @Test
    void sessionCookies() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

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
    }

    @Test
    void mediaPath() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        data(response);
        assertCookie(response.getCookie("htw_media"), "/api/v1/media/private/");
    }

    @Test
    void mediaTicket() throws Exception {
        UserRow user = user(true);
        long before = Values.now();

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        SessionRow session = session(response);
        Cookie media = response.getCookie("htw_media");
        assertNotNull(media);
        Map<String, Object> payload = crypto.open("media-read", media.getValue(), "TOKEN_INVALID");
        assertEquals(user.getId(), payload.get("user"));
        assertEquals(session.getId(), payload.get("session"));
        long expires = ((Number) payload.get("exp")).longValue();
        assertTrue(expires >= before + SESSION_MILLIS);
        assertTrue(expires <= Values.now() + SESSION_MILLIS);
        assertEquals(3, payload.size());
    }

    @Test
    void normalizes() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse response = login(
                "  " + user.getEmail().toUpperCase(Locale.ROOT) + "  ", PASSWORD);

        assertEquals(user.getId(), session(response).getUserId());
        assertEquals(user.getEmail(), ((Map<?, ?>) data(response).get("user")).get("email"));
    }

    @Test
    void unverified() throws Exception {
        UserRow user = user(false);

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        assertError(response, 403, "EMAIL_NOT_VERIFIED");
        assertEquals(0, sessionCount(user.getId()));
    }

    @Test
    void wrongPassword() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse response = login(user.getEmail(), "wrong-password");

        assertError(response, 401, "INVALID_CREDENTIALS");
        assertEquals("邮箱或密码错误", json.readMap(response.getContentAsString()).get("message"));
        assertEquals(0, sessionCount(user.getId()));
    }

    @Test
    void unverifiedWrong() throws Exception {
        UserRow user = user(false);

        MockHttpServletResponse response = login(user.getEmail(), "wrong-password");

        assertError(response, 401, "INVALID_CREDENTIALS");
        assertEquals(0, sessionCount(user.getId()));
    }

    @Test
    void unknownUser() throws Exception {
        String email = Values.id() + "@example.test";
        clearInvocations(passwords);

        MockHttpServletResponse response = login(email, PASSWORD);

        assertError(response, 401, "INVALID_CREDENTIALS");
        assertEquals("邮箱或密码错误", json.readMap(response.getContentAsString()).get("message"));
        assertNull(users.email(email));
        // 用真实编码器的调用验证占位摘要校验，不通过计时推测是否执行。
        verify(passwords).matches(eq(PASSWORD), anyString());
    }

    @Test
    void passwordSpaces() throws Exception {
        UserRow user = user(true, " " + PASSWORD + " ");

        data(login(user.getEmail(), " " + PASSWORD + " "));
        assertError(login(user.getEmail(), PASSWORD), 401, "INVALID_CREDENTIALS");

        assertEquals(1, sessionCount(user.getId()));
    }

    @Test
    void separateSessions() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse first = login(user.getEmail(), PASSWORD);
        MockHttpServletResponse second = login(user.getEmail(), PASSWORD);

        SessionRow a = session(first);
        SessionRow b = session(second);
        assertNotEquals(a.getId(), b.getId());
        assertNotEquals(a.getRefreshHash(), b.getRefreshHash());
        assertEquals(2, sessionCount(user.getId()));
    }

    @Test
    void bearerWorks() throws Exception {
        UserRow user = user(true);
        String token = access(login(user.getEmail(), PASSWORD));

        MockHttpServletResponse response = protectedRequest(token);

        assertEquals(200, response.getStatus());
        assertEquals(user.getId(), response.getContentAsString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void revokedSession() throws Exception {
        UserRow user = user(true);
        String token = access(login(user.getEmail(), PASSWORD));
        Viewer viewer = crypto.verifyJwt(token);
        jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", viewer.sessionId());

        assertError(protectedRequest(token), 401, "SESSION_REVOKED");
    }

    @Test
    void expiredSession() throws Exception {
        UserRow user = user(true);
        String token = access(login(user.getEmail(), PASSWORD));
        Viewer viewer = crypto.verifyJwt(token);
        jdbc.update("UPDATE sessions SET expires_at = ? WHERE id = ?", Values.now() - 1_000, viewer.sessionId());

        assertError(protectedRequest(token), 401, "SESSION_REVOKED");
    }

    @Test
    void expiredAccess() throws Exception {
        UserRow user = user(true);
        SessionRow session = session(login(user.getEmail(), PASSWORD));
        String expired = crypto.jwt(user.getId(), session.getId(), -1);

        assertError(protectedRequest(expired), 401, "ACCESS_TOKEN_EXPIRED");
    }

    @Test
    void sessionMismatch() throws Exception {
        UserRow user = user(true);
        SessionRow session = session(login(user.getEmail(), PASSWORD));
        UserRow other = user(true);
        String mismatched = crypto.jwt(other.getId(), session.getId(), 900);

        assertError(protectedRequest(mismatched), 401, "SESSION_REVOKED");
    }

    @Test
    void missingCsrf() throws Exception {
        UserRow user = user(true);

        MockHttpServletResponse response = mvc.perform(post(LOGIN)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andReturn().getResponse();

        assertError(response, 403, "CSRF_INVALID");
        assertEquals(0, sessionCount(user.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingPassword", "wrongType", "extraField", "invalidEmail"})
    void badInput(String kind) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("email", Values.id() + "@example.test");
        body.put("password", PASSWORD);
        switch (kind) {
            case "missingPassword" -> body.remove("password");
            case "wrongType" -> body.put("password", 12345);
            case "extraField" -> body.put("unexpected", true);
            case "invalidEmail" -> body.put("email", "invalid-email");
            default -> throw new IllegalArgumentException(kind);
        }

        assertError(login(body), 422, "VALIDATION_FAILED");
    }

    @Test
    void loginLimit() throws Exception {
        UserRow user = user(true);
        for (int i = 0; i < 10; i++) {
            assertError(login(user.getEmail(), "wrong-password"), 401, "INVALID_CREDENTIALS");
        }

        MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

        assertError(response, 429, "RATE_LIMITED");
        assertNotNull(response.getHeader("Retry-After"));
        assertEquals(0, sessionCount(user.getId()));
    }

    private UserRow user(boolean verified) {
        return user(verified, PASSWORD);
    }

    private UserRow user(boolean verified, String password) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, ?, '测试用户', '', ?, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, id + "@example.test", "login_" + id.replace("-", "").substring(0, 12),
                passwords.encode(password), verified, Values.now());
        fixtureUsers.add(id);
        return users.user(id);
    }

    private MockHttpServletResponse login(String email, String password) throws Exception {
        return login(Map.of("email", email, "password", password));
    }

    private MockHttpServletResponse login(Map<String, Object> body) throws Exception {
        String context = crypto.randomToken();
        return mvc.perform(post(LOGIN).header("Origin", ORIGIN)
                        .cookie(new Cookie("htw_csrf_context", context))
                        .header("X-CSRF-Token", auth.csrf(context).csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json.write(body))
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        }))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse protectedRequest(String token) throws Exception {
        return mvc.perform(get(PROBE).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private String access(MockHttpServletResponse response) throws Exception {
        return (String) data(response).get("accessToken");
    }

    private SessionRow session(MockHttpServletResponse response) throws Exception {
        SessionRow row = users.session(crypto.verifyJwt(access(response)).sessionId());
        assertNotNull(row);
        return row;
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
        assertTrue(cookie.getMaxAge() > 30 * 86_400 - 60);
        assertTrue(cookie.getMaxAge() <= 30 * 86_400);
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
