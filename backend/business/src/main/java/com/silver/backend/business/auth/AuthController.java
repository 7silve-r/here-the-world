package com.silver.backend.business.auth;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.infrastructure.Crypto;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1/auth")
public class AuthController {
    @Autowired
    private AuthService service;
    @Autowired
    private Crypto crypto;
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
        response.setHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(name, value)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Lax")
                        .path("api/v1/auth")
                        .maxAge(seconds)
                        .build()
                        .toString());
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
}
