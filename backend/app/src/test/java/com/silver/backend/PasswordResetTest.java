package com.silver.backend;

import com.silver.backend.business.RequestContext;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.dto.EmailInput;
import com.silver.backend.business.dto.LoginInput;
import com.silver.backend.business.dto.ResetPasswordInput;
import com.silver.backend.business.persistence.ResetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.MailGateway;
import com.silver.backend.infrastructure.TaskQueue;
import com.silver.backend.infrastructure.TransactionHelper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
@Import(PasswordResetTest.ProbeController.class)
@TestPropertySource(properties = "htw.frontend-origin=https://frontend.example.test")
class PasswordResetTest extends BackendTestSupport {
    private static final String FORGOT = "/api/v1/auth/forgot-password";
    private static final String RESET = "/api/v1/auth/reset-password";
    private static final String PROBE = "/api/v1/test/reset-user";
    private static final String FRONTEND = "https://frontend.example.test";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String PASSWORD = "Original-password-123";
    private static final String NEW_PASSWORD = "Replacement-password-456";
    private static final Pattern TOKEN = Pattern.compile(
            Pattern.quote(FRONTEND + "/reset-password#token=") + "([A-Za-z0-9_-]{43})");
    private static final AtomicInteger NEXT_IP = new AtomicInteger(100);

    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private AuthMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private TaskQueue queue;
    @Autowired private TaskQueue.TaskMapper tasks;
    @MockitoBean private MailGateway mail;
    @MockitoSpyBean private TransactionHelper tx;

    private final String clientIp = "192.0.2." + NEXT_IP.getAndIncrement();
    private final List<String> fixtureUsers = new ArrayList<>();

    @BeforeEach
    void clearTasks() {
        jdbc.update("DELETE FROM background_tasks");
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM background_tasks");
        for (String user : fixtureUsers) {
            jdbc.update("DELETE FROM password_resets WHERE user_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void issuesMail() throws Exception {
        String user = user(true);
        long before = Values.now();

        accepted(forgot(users.user(user).getEmail()));

        long after = Values.now();
        Proof proof = decode(taskIds().get(0));
        ResetRow row = row(proof);
        assertEquals(user, row.getUserId());
        assertEquals(crypto.digest(proof.token()), row.getTokenHash());
        assertFalse(row.isConsumed());
        assertTrue(row.getExpiresAt() >= before + 1_800_000);
        assertTrue(row.getExpiresAt() <= after + 1_800_000);
        assertEquals("RESET", proof.payload().get("purpose"));
        assertEquals(user, proof.payload().get("user"));
        assertEquals(users.user(user).getEmail(), proof.payload().get("email"));
        assertEquals(row.getTokenHash(), proof.payload().get("proof"));
        assertFalse(((String) proof.payload().get("body")).contains(row.getTokenHash()));
        assertFalse(tasks.get(proof.task()).payload.contains(proof.token()));
        assertEquals(1, resetCount(user));
        assertEquals(1, taskIds().size());
        verifyNoInteractions(mail);
    }

    @Test
    void unknownEmail() throws Exception {
        accepted(forgot(Values.id() + "@example.test"));
        assertTrue(taskIds().isEmpty());
    }

    @Test
    void unverifiedEmail() throws Exception {
        String user = user(false);

        accepted(forgot(users.user(user).getEmail()));

        assertEquals(0, resetCount(user));
        assertTrue(taskIds().isEmpty());
    }

    @Test
    void normalizes() throws Exception {
        String user = user(true);
        String email = users.user(user).getEmail();

        accepted(forgot("  " + email.toUpperCase(Locale.ROOT) + "  "));

        Proof proof = decode(taskIds().get(0));
        assertEquals(user, row(proof).getUserId());
        assertEquals(email, proof.payload().get("email"));
    }

    @Test
    void forgotLimit() throws Exception {
        String user = user(true);
        String email = users.user(user).getEmail();
        accepted(forgot(email));

        assertError(forgot(email), 429, "RATE_LIMITED");

        assertEquals(1, resetCount(user));
        assertEquals(1, taskIds().size());
    }

    @Test
    void dispatchesMail() {
        Proof proof = proof(user(true));

        queue.tick();

        verifySent(proof);
        verifyNoMoreInteractions(mail);
        assertEquals("DONE", tasks.get(proof.task()).state);
        assertEquals("", tasks.get(proof.task()).payload);
        assertFalse(row(proof).isConsumed());
    }

    @Test
    void newestWins() throws Exception {
        String user = user(true);
        // 直接调用 Service 验证凭证替换；HTTP 冷却由 forgotLimit 覆盖。
        Proof old = proof(user);
        Proof current = proof(user);
        assertTrue(row(old).isConsumed());
        assertFalse(row(current).isConsumed());
        assertNotEquals(old.token(), current.token());
        assertError(reset(old.token(), NEW_PASSWORD), 422, "RESET_TOKEN_INVALID");

        queue.tick();
        queue.tick();

        verifySent(current);
        verifyNoMoreInteractions(mail);
        succeeded(reset(current.token(), NEW_PASSWORD));
        assertTrue(row(old).isConsumed());
        assertTrue(row(current).isConsumed());
    }

    @Test
    void expiredMail() {
        Proof proof = proof(user(true));
        jdbc.update("UPDATE password_resets SET expires_at = ? WHERE token_hash = ?",
                Values.now() - 1_000, crypto.digest(proof.token()));

        queue.tick();

        verifyNoInteractions(mail);
        assertEquals("DONE", tasks.get(proof.task()).state);
        assertFalse(row(proof).isConsumed());
    }

    @Test
    void consumedMail() {
        String user = user(true);
        Proof proof = proof(user);
        users.consumeResets(user);

        queue.tick();

        verifyNoInteractions(mail);
        assertEquals("DONE", tasks.get(proof.task()).state);
    }

    @Test
    void resets() throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        AuthService.LoginResult first = login(user);
        AuthService.LoginResult second = login(user);
        String previousHash = users.user(user).getPasswordHash();

        MockHttpServletResponse response = reset(proof.token(), NEW_PASSWORD);

        succeeded(response);
        String hash = users.user(user).getPasswordHash();
        assertNotEquals(previousHash, hash);
        assertNotEquals(NEW_PASSWORD, hash);
        assertTrue(passwords.matches(NEW_PASSWORD, hash));
        assertFalse(passwords.matches(PASSWORD, hash));
        assertTrue(row(proof).isConsumed());
        assertTrue(users.session(session(first).getId()).isRevoked());
        assertTrue(users.session(session(second).getId()).isRevoked());
        assertError(protectedRequest(first.data().accessToken()), 401, "SESSION_REVOKED");
        ApiException invalid = assertThrows(ApiException.class, () -> auth.refresh(second.refresh()));
        assertEquals("REFRESH_INVALID", invalid.code);
        assertEquals(2, response.getHeaders(HttpHeaders.SET_COOKIE).size());
        assertCleared(response.getCookie("htw_refresh"), "/api/v1/auth");
        assertCleared(response.getCookie("htw_media"), "/api/v1/media/private/");
        assertThrows(ApiException.class, () -> login(user));
        assertNotNull(auth.login(new LoginInput(users.user(user).getEmail(), NEW_PASSWORD)));
    }

    @Test
    void onlyOwner() throws Exception {
        String owner = user(true);
        Proof ownProof = proof(owner);
        SessionRow ownSession = session(login(owner));
        String other = user(true);
        Proof otherProof = proof(other);
        ResetRow otherReset = row(otherProof);
        SessionRow otherSession = session(login(other));
        String otherHash = users.user(other).getPasswordHash();

        succeeded(reset(ownProof.token(), NEW_PASSWORD));

        assertTrue(users.session(ownSession.getId()).isRevoked());
        assertEquals(otherSession, users.session(otherSession.getId()));
        assertEquals(otherReset, row(otherProof));
        assertEquals(otherHash, users.user(other).getPasswordHash());
    }

    @Test
    void singleUse() throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        succeeded(reset(proof.token(), NEW_PASSWORD));
        String hash = users.user(user).getPasswordHash();

        assertError(reset(proof.token(), "Another-password-789"), 422, "RESET_TOKEN_INVALID");

        assertEquals(hash, users.user(user).getPasswordHash());
        assertTrue(row(proof).isConsumed());
    }

    @Test
    void expiredToken() throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        String hash = users.user(user).getPasswordHash();
        jdbc.update("UPDATE password_resets SET expires_at = ? WHERE token_hash = ?",
                Values.now() - 1_000, crypto.digest(proof.token()));

        assertError(reset(proof.token(), NEW_PASSWORD), 410, "RESET_TOKEN_EXPIRED");

        assertEquals(hash, users.user(user).getPasswordHash());
        assertFalse(row(proof).isConsumed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-token", "0000000000000000000000000000000000000000000", "abc.def.ghi"})
    void invalidToken(String token) throws Exception {
        assertError(reset(token, NEW_PASSWORD), 422, "RESET_TOKEN_INVALID");
    }

    @Test
    void hashNotToken() throws Exception {
        String user = user(true);
        Proof proof = proof(user);

        assertError(reset(row(proof).getTokenHash(), NEW_PASSWORD), 422, "RESET_TOKEN_INVALID");

        assertFalse(row(proof).isConsumed());
        assertTrue(passwords.matches(PASSWORD, users.user(user).getPasswordHash()));
    }

    @Test
    void consumedToken() throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        users.consumeResets(user);

        assertError(reset(proof.token(), NEW_PASSWORD), 422, "RESET_TOKEN_INVALID");

        assertTrue(passwords.matches(PASSWORD, users.user(user).getPasswordHash()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingToken", "missingPassword", "shortPassword", "longPassword", "wrongType", "extraField"})
    void badInput(String kind) throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        var body = new LinkedHashMap<String, Object>();
        body.put("token", proof.token());
        body.put("newPassword", NEW_PASSWORD);
        switch (kind) {
            case "missingToken" -> body.remove("token");
            case "missingPassword" -> body.remove("newPassword");
            case "shortPassword" -> body.put("newPassword", "short");
            case "longPassword" -> body.put("newPassword", "x".repeat(129));
            case "wrongType" -> body.put("newPassword", 12345);
            case "extraField" -> body.put("repassword", NEW_PASSWORD);
            default -> throw new IllegalArgumentException(kind);
        }

        assertError(send(RESET, body), 422, "VALIDATION_FAILED");

        assertFalse(row(proof).isConsumed());
        assertTrue(passwords.matches(PASSWORD, users.user(user).getPasswordHash()));
    }

    @Test
    void passwordSpaces() throws Exception {
        String user = user(true);
        Proof proof = proof(user);

        succeeded(reset(proof.token(), " " + NEW_PASSWORD + " "));

        String hash = users.user(user).getPasswordHash();
        assertTrue(passwords.matches(" " + NEW_PASSWORD + " ", hash));
        assertFalse(passwords.matches(NEW_PASSWORD, hash));
    }

    @ParameterizedTest
    @ValueSource(strings = {"forgot", "reset", "origin"})
    void needsCsrf(String kind) throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        Map<String, Object> body = "forgot".equals(kind)
                ? Map.of("email", users.user(user).getEmail())
                : Map.of("token", proof.token(), "newPassword", NEW_PASSWORD);
        var request = post("forgot".equals(kind) ? FORGOT : RESET)
                .header("Origin", "origin".equals(kind) ? "https://other.example.test" : ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        if ("origin".equals(kind)) {
            String context = crypto.randomToken();
            request.cookie(new Cookie("htw_csrf_context", context))
                    .header("X-CSRF-Token", auth.csrf(context).csrfToken());
        }

        assertError(perform(request), 403, "CSRF_INVALID");

        assertFalse(row(proof).isConsumed());
        assertEquals(1, taskIds().size());
        assertTrue(passwords.matches(PASSWORD, users.user(user).getPasswordHash()));
    }

    @Test
    void concurrentUse() throws Exception {
        String user = user(true);
        Proof proof = proof(user);
        SessionRow session = session(login(user));
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Attempt>>();
        try {
            for (String password : List.of(NEW_PASSWORD + "-A", NEW_PASSWORD + "-B")) {
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        auth.reset(new ResetPasswordInput(proof.token(), password));
                        return new Attempt(password, 200, null);
                    } catch (ApiException failure) {
                        return new Attempt(password, failure.status, failure.code);
                    }
                }));
            }
            start.countDown();
            var attempts = new ArrayList<Attempt>();
            for (Future<Attempt> future : futures) attempts.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, attempts.stream().filter(attempt -> attempt.status() == 200).count());
            assertEquals(1, attempts.stream().filter(attempt ->
                    attempt.status() == 422 && "RESET_TOKEN_INVALID".equals(attempt.code())).count());
            Attempt winner = attempts.stream().filter(attempt -> attempt.status() == 200)
                    .findFirst().orElseThrow();
            assertTrue(passwords.matches(winner.password(), users.user(user).getPasswordHash()));
            assertTrue(row(proof).isConsumed());
            assertTrue(users.session(session.getId()).isRevoked());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rollsBack() {
        String user = user(true);
        Proof proof = proof(user);
        ResetRow before = row(proof);
        SessionRow session = session(login(user));
        String hash = users.user(user).getPasswordHash();

        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            auth.reset(new ResetPasswordInput(proof.token(), NEW_PASSWORD));
            throw new IllegalStateException("Rollback test transaction");
        }));

        assertEquals(hash, users.user(user).getPasswordHash());
        assertEquals(before, row(proof));
        assertEquals(session, users.session(session.getId()));
        assertTrue(auth.reset(new ResetPasswordInput(proof.token(), NEW_PASSWORD)).success());
    }

    @Test
    void reissuedBeforeLock() throws Exception {
        String user = user(true);
        Proof old = proof(user);
        var resetThread = new AtomicReference<Thread>();
        var hintRead = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread() == resetThread.get()) {
                hintRead.countDown();
                assertTrue(proceed.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(tx).write(anyCollection(), any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApiException> pending = pool.submit(() -> {
                resetThread.set(Thread.currentThread());
                return assertThrows(ApiException.class,
                        () -> auth.reset(new ResetPasswordInput(old.token(), NEW_PASSWORD)));
            });
            assertTrue(hintRead.await(5, TimeUnit.SECONDS));
            Proof current = proof(user);
            proceed.countDown();
            assertEquals("RESET_TOKEN_INVALID", pending.get(10, TimeUnit.SECONDS).code);
            assertFalse(row(current).isConsumed());
            assertTrue(passwords.matches(PASSWORD, users.user(user).getPasswordHash()));
            succeeded(reset(current.token(), NEW_PASSWORD));
        } finally {
            proceed.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private String user(boolean verified) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, ?, '测试用户', '', ?, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, id + "@example.test", "reset_" + id.replace("-", "").substring(0, 12),
                passwords.encode(PASSWORD), verified, Values.now());
        fixtureUsers.add(id);
        return id;
    }

    private AuthService.LoginResult login(String user) {
        return auth.login(new LoginInput(users.user(user).getEmail(), PASSWORD));
    }

    private SessionRow session(AuthService.LoginResult login) {
        SessionRow row = users.session(crypto.verifyJwt(login.data().accessToken()).sessionId());
        assertNotNull(row);
        return row;
    }

    private Proof proof(String user) {
        Set<String> previous = new HashSet<>(taskIds());
        assertTrue(auth.forgot(new EmailInput(users.user(user).getEmail())).accepted());
        List<String> added = taskIds().stream().filter(id -> !previous.contains(id)).toList();
        assertEquals(1, added.size());
        return decode(added.get(0));
    }

    private Proof decode(String id) {
        Map<String, Object> payload = json.readMap(crypto.decrypt(tasks.get(id).payload));
        var matcher = TOKEN.matcher((String) payload.get("body"));
        assertTrue(matcher.find(), "邮件应包含前端重置地址和原始令牌");
        return new Proof(matcher.group(1), id, payload);
    }

    private ResetRow row(Proof proof) {
        ResetRow row = users.reset(crypto.digest(proof.token()));
        assertNotNull(row);
        return row;
    }

    private List<String> taskIds() {
        return jdbc.queryForList("SELECT id FROM background_tasks WHERE kind = 'MAIL'", String.class);
    }

    private int resetCount(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM password_resets WHERE user_id = ?", Integer.class, user);
    }

    private void verifySent(Proof proof) {
        verify(mail).send((String) proof.payload().get("email"),
                (String) proof.payload().get("subject"), (String) proof.payload().get("body"));
    }

    private MockHttpServletResponse forgot(String email) throws Exception {
        return send(FORGOT, Map.of("email", email));
    }

    private MockHttpServletResponse reset(String token, String password) throws Exception {
        return send(RESET, Map.of("token", token, "newPassword", password));
    }

    private MockHttpServletResponse send(String path, Map<String, Object> body) throws Exception {
        String context = crypto.randomToken();
        return perform(post(path).header("Origin", ORIGIN)
                .cookie(new Cookie("htw_csrf_context", context))
                .header("X-CSRF-Token", auth.csrf(context).csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body)));
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
        assertEquals(202, response.getStatus(), response.getContentAsString());
        assertEquals(Map.of("accepted", true, "resendAfterSeconds", 60),
                json.readMap(response.getContentAsString()).get("data"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private void succeeded(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals(Map.of("success", true), json.readMap(response.getContentAsString()).get("data"));
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

    private record Proof(String token, String task, Map<String, Object> payload) {}
    private record Attempt(String password, int status, String code) {}

    @TestComponent
    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        String user(HttpServletRequest request) {
            return RequestContext.viewer(request).userId();
        }
    }
}
