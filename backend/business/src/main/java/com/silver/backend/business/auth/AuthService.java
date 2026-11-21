package com.silver.backend.business.auth;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.*;
import com.silver.backend.business.persistence.CodeRow;
import com.silver.backend.business.persistence.ResetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.TaskQueue;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AuthService {

    private final Crypto crypto;
    @Autowired
    private AuthMapper mapper;
    @Autowired
    private TransactionHelper tx;
    private final PasswordEncoder passwordEncoder;
    @Autowired
    private TaskQueue taskQueue;
    @Autowired
    private Views views;
    private final String dummy;
    @Value("${htw.frontend-origin}")
    private String frontend;

    public AuthService(Crypto crypto, PasswordEncoder passwordEncoder) {
        this.crypto = crypto;
        this.passwordEncoder = passwordEncoder;
        this.dummy = passwordEncoder.encode(crypto.randomToken());
    }

    public Csrf csrf(String context) {
        return new Csrf(
                crypto.ticket("csrf", Values.map("context", crypto.digest(context)), 1_800_000),
                Values.time(Values.now() + 1_800_000));
    }

    public void verifyCsrf(String context, String token) {
        try {
            var p = crypto.open("csrf", token, "CSRF_INVALID");
            if (p == null || !Objects.equals(p.get("context"), crypto.digest(context))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new ApiException(403, "CSRF_INVALID", "CSRF 上下文或令牌无效");
        }
    }

    public void checkSession(Viewer viewer) {
        SessionRow row = mapper.session(viewer.sessionId());
        if (row == null
                || row.isRevoked()
                || row.getExpiresAt() <= Values.now()
                || !row.getUserId().equals(viewer.userId())) {
            throw new ApiException(401, "SESSION_REVOKED", "会话已失效");
        }
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private MessageAccepted accepted() {
        return new MessageAccepted(true, 60L);
    }

    private void issueCode(UserRow user){
        CodeRow previous = mapper.code(user.getId());
        String code;
        do {
            code = crypto.code();
        } while (previous != null
            && previous.getCodeHash().equals(crypto.keyedDigest("verification", user.getId() + ":" + code)));
        CodeRow row = new CodeRow();
        row.setUserId(user.getId());
        row.setCodeHash(crypto.keyedDigest("verification", user.getId() + ":" + code));
        row.setExpiresAt(Values.now() + 600_000);
        row.setSentAt(Values.now());
        mapper.deleteCode(user.getId());
        mapper.insertCode(row);
        taskQueue.enqueue(
                "MAIL",
                Values.map(
                        "purpose",
                        "VERIFY",
                        "user",
                        user.getId(),
                        "proof",
                        row.getCodeHash(),
                        "email",
                        user.getEmail(),
                        "subject",
                        "Here The World 邮箱验证",
                        "body",
                        "你的验证码：" + code + "。10 分钟内有效。"
                )
        );
    }

    public MessageAccepted register(RegisterInput in) {
        String displayName = Requests.trimmed(in.displayName(), "displayName", true);
        String email = normalizeEmail(in.email());
        return tx.write(
                List.of("05:email:" + email, "05:username:" + in.username().toLowerCase(Locale.ROOT)),
                () -> {
                    UserRow existing = mapper.email(email);
                    if (existing != null) {
                        throw new ApiException(409, "EMAIL_TAKEN", "邮箱已注册，请登录或找回密码");
                    }
                    if (mapper.username(in.username()) != null) {
                        throw new ApiException(409, "USERNAME_TAKEN", "用户名已被占用");
                    }
                    UserRow u = new UserRow();
                    u.setId(Values.id());
                    u.setEmail(email);
                    u.setUsername(in.username());
                    u.setPasswordHash(passwordEncoder.encode(in.password()));
                    u.setDisplayName(displayName);
                    u.setBio("");
                    u.setTheme("SYSTEM");
                    u.setMotion("SYSTEM");
                    u.setEmailVerified(false);
                    u.setAutoPlayMedia(true);
                    u.setCreatedAt(Values.now());
                    mapper.insertUser(u);
                    issueCode(u);
                    return accepted();
                }
        );
    }

    public boolean mailStillRelevant(Map<String, Object> payload) {
        if ("VERIFY".equals(payload.get("purpose"))) {
            UserRow user = mapper.user((String) payload.get("user"));
            CodeRow code = mapper.code((String) payload.get("user"));
            return user != null
                    && !user.isEmailVerified()
                    && code != null
                    && code.getExpiresAt() > Values.now()
                    && Objects.equals(code.getCodeHash(), payload.get("proof"));
        }
        return false;
    }

    private UserRow lockedEmailUser(String email) {
        UserRow hint = mapper.email(normalizeEmail(email));
        if (hint == null) return null;
        tx.lock(List.of("10:user:" + hint.getId()));
        return mapper.user(hint.getId());
    }

    public Ack verify(VerifyEmailInput input) {
        String email = normalizeEmail(input.email());
        ApiException error = tx.write(
            "05:email:" + email,
                () -> {
                    UserRow user = lockedEmailUser(email);
                    if (user != null && user.isEmailVerified()) {
                        return null;
                    }
                    CodeRow code = user == null ? null : mapper.code(user.getId());
                     if (code == null) {
                         return new ApiException(422, "VERIFICATION_CODE_INVALID", "验证码不正确");
                     }
                     if (code.getExpiresAt() <= Values.now()) {
                         return new ApiException(410, "VERIFICATION_CODE_EXPIRED", "验证码已过期");
                     }
                     if (code.getAttempts() >= 5) {
                         return new ApiException(429, "VERIFICATION_ATTEMPTS_EXCEEDED", "验证尝试次数超限", List.of(), 60);
                     }
                     mapper.attempt(user.getId());
                     if (!crypto.keyedDigest("verification", user.getId() + ":" + input.code())
                             .equals(code.getCodeHash())) {
                         return new ApiException(422, "VERIFICATION_CODE_INVALID", "验证码不正确");
                     }
                     user.setEmailVerified(true);
                     mapper.updateUser(user);
                     mapper.deleteCode(user.getId());
                    return null;
                }
        );
        if (error != null) {
            throw error;
        }
        return new Ack(true);
    }

    public MessageAccepted resend(EmailInput input) {
        String email = normalizeEmail(input.email());
        return tx.write(
                "05:email:" + email,
                () -> {
                    UserRow user = lockedEmailUser(email);
                    if (user != null && !user.isEmailVerified()) {
                        CodeRow code = mapper.code(user.getId());
                        if (code == null || Values.now() - code.getSentAt() >= 60_000) {
                            issueCode(user);
                        }
                    }
                    return accepted();
                }
        );
    }

    public record LoginResult (AuthSession data, String refresh, String media, long remainingSeconds) {}

    private LoginResult result(SessionRow row, String refresh) {
        long remaining = Math.max(row.getExpiresAt() - Values.now(), 1);
        return new LoginResult(
                new AuthSession(crypto.jwt(row.getUserId(), row.getId(), 900),
                        "Bearer",
                        900L,
                        views.me(row.getUserId())),
                refresh,
                crypto.ticket("media-read",
                        Values.map("user", row.getUserId(), "session", row.getId()),
                        remaining),
                Math.max(remaining / 1000, 1)
        );
    }

    public LoginResult login(LoginInput input) {
        return tx.write(
            "05:email:" + normalizeEmail(input.email()),
                () -> {
                    UserRow user = lockedEmailUser(input.email());
                    boolean matches = passwordEncoder.matches(input.password(), user == null ? dummy : user.getPasswordHash());
                    if (!matches || user == null) {
                        throw new ApiException(401, "INVALID_CREDENTIALS", "邮箱或密码错误");
                    }
                    if (!user.isEmailVerified()) {
                        throw new ApiException(403, "EMAIL_NOT_VERIFIED","请先验证邮箱");
                    }
                    String refresh = crypto.randomToken();
                    SessionRow row = new SessionRow();
                    row.setId(Values.id());
                    row.setUserId(user.getId());
                    row.setRefreshHash(crypto.digest(refresh));
                    row.setExpiresAt(Values.now() + 30L * 86_400_000);
                    row.setCreatedAt(Values.now());
                    row.setRevoked(false);
                    mapper.insertSession(row);
                    return result(row, refresh);
                }
        );
    }

    public LoginResult refresh(String token) {
        SessionRow hint = token == null ? null : mapper.refresh(crypto.digest(token));
        return tx.write(
                hint == null ? List.of() : List.of("10:user:" + hint.getUserId()),
                () -> {
                    SessionRow row = token == null ? null : mapper.refresh(crypto.digest(token));
                    if (row == null || row.isRevoked() || row.getExpiresAt() <= Values.now()) {
                        throw new ApiException(401, "REFRESH_INVALID", "刷新凭证无效");
                    }
                    String replacement = crypto.randomToken();
                    row.setRefreshHash(crypto.digest(replacement));
                    mapper.updateSession(row);
                    return result(row, replacement);
                }
        );
    }

    public Ack logout(String token) {
        SessionRow hint = token == null ? null : mapper.refresh(crypto.digest(token));
        return tx.write(
                hint == null ? List.of() : List.of("10:user:" + hint.getUserId()),
                () -> {
                    if (hint != null) {
                        SessionRow row = mapper.session(hint.getId());
                        if (row != null) {
                            row.setRevoked(true);
                            mapper.updateSession(row);
                        }
                    }
                    return new Ack(true);
                }
        );
    }

    public MessageAccepted forget(EmailInput input) {
        return tx.write(
                "05:email:" + normalizeEmail(input.email()),
                () -> {
                    UserRow user = lockedEmailUser(input.email());
                    if (user != null && user.isEmailVerified()) {
                        String token = crypto.randomToken();
                        mapper.consumeResets(user.getId());
                        ResetRow row = new ResetRow();
                        row.setTokenHash(crypto.digest(token));
                        row.setUserId(user.getId());
                        row.setExpiresAt(Values.now() + 1_800_000);
                        row.setConsumed(false);
                        mapper.insertReset(row);
                        taskQueue.enqueue(
                                "MAIL",
                                Values.map(
                                        "purpose",
                                        "RESET",
                                        "user",
                                        user.getId(),
                                        "proof",
                                        row.getTokenHash(),
                                        "email",
                                        user.getEmail(),
                                        "subject",
                                        "Here the World 重置密码",
                                        "body",
                                        "30 分钟内打开以下链接：\n" + frontend + "/reset-password#token=" + token));
                    }
                    return accepted();
                }
        );
    }
}
