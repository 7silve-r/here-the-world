package com.silver.backend.business.auth;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.dto.EmailInput;
import com.silver.backend.business.dto.LoginInput;
import com.silver.backend.business.dto.RegisterInput;
import com.silver.backend.business.dto.VerifyEmailInput;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.Reply;
import com.silver.backend.infrastructure.Crypto;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("api/v1/auth")
public class AuthController {
    @Autowired
    private AuthService service;
    @Autowired
    private Crypto crypto;
    @Autowired
    private Requests requests;
    @Autowired
    private RequestControl control;
    @Value("${htw.secure-cookies:true}")
    private boolean secure;

    public static String getCookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (name.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private void setCookie(HttpServletResponse response, String name, String value, long seconds) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(name, value)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Lax")
                        .path("htw_media".equals(name) ? "/api/v1/media/private/" : "/api/v1/auth")
                        .maxAge(seconds)
                        .build()
                        .toString());
    }

    private void limit(HttpServletRequest request, String email, String operation, int max, int seconds) {
        control.limit(operation + "-source", request.getRemoteAddr(), max * 3, seconds);
        control.limit(operation + "-account", AuthService.normalizeEmail(email), max, seconds);
    }

    @GetMapping("/csrf")
    public ResponseEntity<?> csrf(HttpServletRequest req,  HttpServletResponse res) {
        String context = getCookie(req, "htw_csrf_context");
        if (context == null || !context.matches("[A-Za-z0-9_-]{43}")) {
            context = crypto.randomToken();
        }
        setCookie(res, "htw_csrf_context", context, 30L * 86400);
        return HttpReplies.ok(req, service.csrf(context));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        var input = requests.decode("RegisterInput", body, RegisterInput.class);
        limit(req, input.email(), "register", 5, 3600);
        return HttpReplies.reply(req, Reply.of(202, service.register(input)));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verify(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        var input = requests.decode("VerifyEmailInput", body, VerifyEmailInput.class);
        limit(req, input.email(), "verify-email", 20, 60);
        return HttpReplies.ok(req, service.verify(input));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resend(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        var input = requests.decode("EmailInput", body, EmailInput.class);
        limit(req, input.email(), "resend", 1, 60);
        return HttpReplies.reply(req, Reply.of(202, service.resend(input)));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, Object> body, HttpServletRequest req, HttpServletResponse res) {
        LoginInput input = requests.decode("LoginInput", body, LoginInput.class);
        limit(req, input.email(), "login", 10, 60);
        AuthService.LoginResult result = service.login(input);
        setCookie(res, "htw_refresh", result.refresh(), result.remainingSeconds());
        setCookie(res, "htw_media", result.media(), result.remainingSeconds());
        return HttpReplies.ok(req, result.data());
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest req,  HttpServletResponse res) {
        control.limit("refresh-source", req.getRemoteAddr(), 120, 60);
        AuthService.LoginResult result = service.refresh(getCookie(req, "htw_refresh"));
        setCookie(res, "htw_refresh", result.refresh(), result.remainingSeconds());
        setCookie(res, "htw_media", result.media(), result.remainingSeconds());
        return HttpReplies.ok(req, result.data());
    }
}
