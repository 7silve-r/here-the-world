package com.silver.backend;

import com.silver.backend.bootstrap.ApiSecurityFilter;
import com.silver.backend.bootstrap.HttpErrors;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Import(SecurityFlowTest.ProbeController.class)
class SecurityFlowTest extends BackendTestSupport {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String CONTEXT = "htw_csrf_context";
    private static final String AUTH_PROBE = "/api/v1/auth/test-probe";
    private static final String PROTECTED_PROBE = "/api/v1/test/protected";
    private static final String UPLOAD = "/api/v1/media/upload";
    private static final int MAX_JSON_BYTES = 1_048_576;

    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private HttpErrors errors;
    @Autowired private RequestControl control;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void csrfFields() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csrfToken").isString())
                .andReturn().getResponse();
        Map<?, ?> data = (Map<?, ?>) json.readMap(response.getContentAsString()).get("data");
        assertTrue(Instant.parse((String) data.get("expiresAt")).isAfter(Instant.now()));
    }

    @Test
    void csrfCookie() throws Exception {
        String context = crypto.randomToken();
        mvc.perform(get("/api/v1/auth/csrf").cookie(new Cookie(CONTEXT, context)))
                .andExpect(status().isOk())
                .andExpect(cookie().value(CONTEXT, context))
                .andExpect(cookie().path(CONTEXT, "/api/v1/auth"))
                .andExpect(cookie().maxAge(CONTEXT, 30 * 86_400))
                .andExpect(cookie().httpOnly(CONTEXT, true))
                .andExpect(cookie().secure(CONTEXT, true))
                .andExpect(cookie().sameSite(CONTEXT, "Lax"));
    }

    @Test
    void csrfChecks() throws Exception {
        String context = crypto.randomToken();
        String token = crypto.ticket("csrf", Map.of("context", crypto.digest(context)), 60_000);
        mvc.perform(post(AUTH_PROBE).header("Origin", ORIGIN)
                        .cookie(new Cookie(CONTEXT, context)).header("X-CSRF-Token", token))
                .andExpect(status().isOk());
        mvc.perform(post(AUTH_PROBE).header("Origin", "https://other.example.test")
                        .cookie(new Cookie(CONTEXT, context)).header("X-CSRF-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post(AUTH_PROBE).header("Origin", ORIGIN)
                        .cookie(new Cookie(CONTEXT, crypto.randomToken())).header("X-CSRF-Token", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post(AUTH_PROBE).header("Origin", ORIGIN)
                        .cookie(new Cookie(CONTEXT, context)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void liveSession() throws Exception {
        String user = Values.id();
        String session = Values.id();
        long now = Values.now();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'fixture', '测试', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", user.substring(0, 12), now);
        jdbc.update("""
                INSERT INTO sessions (id, user_id, refresh_hash, expires_at, created_at, revoked)
                VALUES (?, ?, ?, ?, ?, FALSE)
                """, session, user, crypto.digest(session), now + 60_000, now);
        String bearer = "Bearer " + crypto.jwt(user, session, 60);

        mvc.perform(get(PROTECTED_PROBE)).andExpect(status().isUnauthorized());
        mvc.perform(get(PROTECTED_PROBE).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().string(user));
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", session);
        mvc.perform(get(PROTECTED_PROBE).header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void rejectsKnownLength() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[MAX_JSON_BYTES + 1]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void uploadUsesRawStream() throws Exception {
        ApiSecurityFilter filter = new ApiSecurityFilter(auth, crypto, errors, control, ORIGIN);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", UPLOAD);
        request.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        request.setContent(new byte[MAX_JSON_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        long[] received = {0};

        filter.doFilter(request, response, (incoming, outgoing) -> {
            assertSame(request, incoming, "二进制上传不应套用 JSON 限流包装器");
            received[0] = incoming.getInputStream().transferTo(OutputStream.nullOutputStream());
        });

        assertEquals(200, response.getStatus());
        assertEquals(MAX_JSON_BYTES + 1L, received[0]);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void limitsUnknownLength(boolean readText) throws Exception {
        ApiSecurityFilter filter = new ApiSecurityFilter(auth, crypto, errors, control, ORIGIN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test/body") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        byte[] body = new byte[MAX_JSON_BYTES + 1];
        Arrays.fill(body, (byte) 'a');
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, outgoing) -> {
            if (readText) {
                incoming.getReader().readLine();
            } else {
                incoming.getInputStream().transferTo(OutputStream.nullOutputStream());
            }
            fail("超过上限后应中断读取");
        });

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"FILE_TOO_LARGE\""));
    }

    @TestComponent
    @RestController
    static class ProbeController {
        @PostMapping(AUTH_PROBE)
        String auth() { return "ok"; }

        @GetMapping(PROTECTED_PROBE)
        String user(HttpServletRequest request) {
            return RequestContext.viewer(request).userId();
        }

    }
}
