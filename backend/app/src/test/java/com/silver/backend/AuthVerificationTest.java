package com.silver.backend;

import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.dto.EmailInput;
import com.silver.backend.business.dto.MessageAccepted;
import com.silver.backend.business.dto.RegisterInput;
import com.silver.backend.business.dto.VerifyEmailInput;
import com.silver.backend.business.persistence.CodeRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TransactionHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// 不加 @Transactional：需要验证业务抛出异常后，尝试次数仍然已经提交。
class AuthVerificationTest extends BackendTestSupport {
    private static final String PASSWORD = "Test-password-123";
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");

    @Autowired private AuthService auth;
    @Autowired private AuthMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private TransactionHelper tx;

    private final Set<String> fixtureEmails = new HashSet<>();

    @AfterEach
    void cleanFixtures() {
        for (String email : fixtureEmails) {
            UserRow user = mapper.email(email);
            if (user == null) continue;
            for (Mail mail : mails(user.getId())) {
                jdbc.update("DELETE FROM background_tasks WHERE id = ?", mail.id());
            }
            jdbc.update("DELETE FROM verification_codes WHERE user_id = ?", user.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
        }
    }

    @Test
    void registers() {
        RegisterInput in = input();
        long before = Values.now();
        accepted(auth.register(new RegisterInput(
                variant(in.email()), in.password(), in.username(), "  测试用户  ")));
        long after = Values.now();

        UserRow user = mapper.email(in.email());
        assertNotNull(user);
        assertNotNull(user.getId());
        assertEquals(in.email(), user.getEmail());
        assertEquals(in.username(), user.getUsername());
        assertEquals("测试用户", user.getDisplayName());
        assertEquals("", user.getBio());
        assertEquals("SYSTEM", user.getTheme());
        assertEquals("SYSTEM", user.getMotion());
        assertFalse(user.isEmailVerified());
        assertTrue(user.isAutoPlayMedia());
        assertNull(user.getAvatarAssetId());
        assertTrue(user.getCreatedAt() >= before && user.getCreatedAt() <= after);
        assertNotEquals(PASSWORD, user.getPasswordHash());
        assertTrue(passwords.matches(PASSWORD, user.getPasswordHash()));

        CodeRow code = mapper.code(user.getId());
        assertNotNull(code);
        assertEquals(0, code.getAttempts());
        assertTrue(code.getSentAt() >= before && code.getSentAt() <= after);
        assertTrue(code.getExpiresAt() >= before + 600_000);
        assertTrue(code.getExpiresAt() <= after + 600_000);

        Mail mail = onlyMail(user.getId());
        assertEquals("PENDING", mail.state());
        assertEquals("VERIFY", mail.payload().get("purpose"));
        assertEquals(user.getEmail(), mail.payload().get("email"));
        assertEquals(code.getCodeHash(), mail.payload().get("proof"));
        assertEquals(
                crypto.keyedDigest("verification", user.getId() + ":" + mailCode(mail)),
                code.getCodeHash());
        assertTrue(auth.mailStillRelevant(mail.payload()));
    }

    @Test
    void emailTaken() {
        UserRow user = register();
        CodeRow previous = mapper.code(user.getId());
        RegisterInput other = input();

        ApiException error = assertThrows(ApiException.class, () -> auth.register(
                new RegisterInput(variant(user.getEmail()), PASSWORD, other.username(), "其他用户")));

        assertError(error, 409, "EMAIL_TAKEN");
        assertEquals(user.getId(), mapper.email(user.getEmail()).getId());
        assertNull(mapper.username(other.username()));
        assertEquals(previous, mapper.code(user.getId()));
        assertEquals(1, mails(user.getId()).size());
    }

    @Test
    void nameTaken() {
        UserRow user = register();
        RegisterInput other = input();

        ApiException error = assertThrows(ApiException.class, () -> auth.register(
                new RegisterInput(other.email(), PASSWORD, user.getUsername(), "其他用户")));

        assertError(error, 409, "USERNAME_TAKEN");
        assertNull(mapper.email(other.email()));
        assertEquals(1, mails(user.getId()).size());
    }

    @Test
    void verifies() {
        UserRow user = register();
        Mail mail = onlyMail(user.getId());
        VerifyEmailInput input = new VerifyEmailInput(variant(user.getEmail()), mailCode(mail));

        assertTrue(auth.verify(input).success());
        assertTrue(mapper.user(user.getId()).isEmailVerified());
        assertNull(mapper.code(user.getId()));
        assertFalse(auth.mailStillRelevant(mail.payload()));
        assertTrue(auth.verify(input).success());
        assertEquals(1, mails(user.getId()).size());
    }

    @Test
    void wrongCode() {
        UserRow user = register();
        String wrong = wrongCode(mailCode(onlyMail(user.getId())));

        for (int attempts = 1; attempts <= 2; attempts++) {
            ApiException error = assertThrows(ApiException.class, () ->
                    auth.verify(new VerifyEmailInput(variant(user.getEmail()), wrong)));
            assertError(error, 422, "VERIFICATION_CODE_INVALID");
            assertEquals(attempts, mapper.code(user.getId()).getAttempts());
            assertFalse(mapper.user(user.getId()).isEmailVerified());
        }
    }

    @Test
    void expiredCode() {
        UserRow user = register();
        Mail mail = onlyMail(user.getId());
        jdbc.update("UPDATE verification_codes SET expires_at = ? WHERE user_id = ?",
                Values.now() - 1_000, user.getId());

        ApiException error = assertThrows(ApiException.class, () ->
                auth.verify(new VerifyEmailInput(user.getEmail(), mailCode(mail))));

        assertError(error, 410, "VERIFICATION_CODE_EXPIRED");
        assertEquals(0, mapper.code(user.getId()).getAttempts());
        assertFalse(mapper.user(user.getId()).isEmailVerified());
        assertFalse(auth.mailStillRelevant(mail.payload()));
    }

    @Test
    void attemptLimit() {
        UserRow user = register();
        String correct = mailCode(onlyMail(user.getId()));
        String wrong = wrongCode(correct);
        for (int i = 0; i < 5; i++) {
            ApiException error = assertThrows(ApiException.class, () ->
                    auth.verify(new VerifyEmailInput(user.getEmail(), wrong)));
            assertError(error, 422, "VERIFICATION_CODE_INVALID");
        }

        ApiException error = assertThrows(ApiException.class, () ->
                auth.verify(new VerifyEmailInput(user.getEmail(), correct)));

        assertError(error, 429, "VERIFICATION_ATTEMPTS_EXCEEDED");
        assertEquals(60, error.retryAfter);
        assertEquals(5, mapper.code(user.getId()).getAttempts());
        assertFalse(mapper.user(user.getId()).isEmailVerified());
    }

    @Test
    void missingCode() {
        UserRow user = register();
        mapper.deleteCode(user.getId());

        ApiException error = assertThrows(ApiException.class, () ->
                auth.verify(new VerifyEmailInput(user.getEmail(), "123456")));

        assertError(error, 422, "VERIFICATION_CODE_INVALID");
        assertNull(mapper.code(user.getId()));
        assertFalse(mapper.user(user.getId()).isEmailVerified());
    }

    @Test
    void unknownEmail() {
        RegisterInput in = input();
        ApiException error = assertThrows(ApiException.class, () ->
                auth.verify(new VerifyEmailInput(variant(in.email()), "123456")));

        assertError(error, 422, "VERIFICATION_CODE_INVALID");
        assertNull(mapper.email(in.email()));
    }

    @Test
    void resendWaits() {
        UserRow user = register();
        jdbc.update("""
                UPDATE verification_codes SET sent_at = ?, attempts = 2 WHERE user_id = ?
                """, Values.now() - 30_000, user.getId());
        CodeRow previous = mapper.code(user.getId());

        accepted(auth.resend(new EmailInput(variant(user.getEmail()))));

        assertEquals(previous, mapper.code(user.getId()));
        assertEquals(1, mails(user.getId()).size());
    }

    @Test
    void resendRenews() {
        UserRow user = register();
        Mail oldMail = onlyMail(user.getId());
        String oldCode = mailCode(oldMail);
        jdbc.update("""
                UPDATE verification_codes SET sent_at = ?, expires_at = ?, attempts = 3
                WHERE user_id = ?
                """, Values.now() - 61_000, Values.now() - 1_000, user.getId());
        CodeRow previous = mapper.code(user.getId());
        long before = Values.now();

        accepted(auth.resend(new EmailInput(variant(user.getEmail()))));

        CodeRow current = mapper.code(user.getId());
        assertNotEquals(previous.getCodeHash(), current.getCodeHash());
        assertEquals(0, current.getAttempts());
        assertTrue(current.getSentAt() >= before);
        assertTrue(current.getExpiresAt() >= before + 600_000);
        List<Mail> queued = mails(user.getId());
        assertEquals(2, queued.size());
        Mail newMail = queued.stream()
                .filter(mail -> current.getCodeHash().equals(mail.payload().get("proof")))
                .findFirst().orElseThrow();
        assertFalse(auth.mailStillRelevant(oldMail.payload()));
        assertTrue(auth.mailStillRelevant(newMail.payload()));

        ApiException error = assertThrows(ApiException.class, () ->
                auth.verify(new VerifyEmailInput(user.getEmail(), oldCode)));
        assertError(error, 422, "VERIFICATION_CODE_INVALID");
        assertEquals(1, mapper.code(user.getId()).getAttempts());
        assertTrue(auth.verify(new VerifyEmailInput(user.getEmail(), mailCode(newMail))).success());
        assertNull(mapper.code(user.getId()));
    }

    @Test
    void resendMissing() {
        UserRow user = register();
        mapper.deleteCode(user.getId());

        accepted(auth.resend(new EmailInput(user.getEmail())));

        CodeRow current = mapper.code(user.getId());
        assertNotNull(current);
        assertEquals(0, current.getAttempts());
        assertEquals(2, mails(user.getId()).size());
        assertTrue(current.getExpiresAt() > Values.now());
    }

    @Test
    void resendVerified() {
        UserRow user = register();
        auth.verify(new VerifyEmailInput(user.getEmail(), mailCode(onlyMail(user.getId()))));

        accepted(auth.resend(new EmailInput(variant(user.getEmail()))));

        assertNull(mapper.code(user.getId()));
        assertTrue(mapper.user(user.getId()).isEmailVerified());
        assertEquals(1, mails(user.getId()).size());
    }

    @Test
    void resendUnknown() {
        RegisterInput in = input();
        Long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_tasks WHERE kind = 'MAIL'", Long.class);

        accepted(auth.resend(new EmailInput(variant(in.email()))));

        assertNull(mapper.email(in.email()));
        assertEquals(before, jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_tasks WHERE kind = 'MAIL'", Long.class));
    }

    @Test
    void reusesEmailLock() {
        UserRow user = register();
        // 加入已有事务时，应复用已持有的邮箱锁，不能再申请另一把顺序靠前的锁。
        accepted(tx.write(
                List.of("05:email:" + user.getEmail(), "10:user:" + user.getId()),
                () -> auth.resend(new EmailInput(variant(user.getEmail())))));
        assertEquals(1, mails(user.getId()).size());
    }

    private RegisterInput input() {
        String suffix = Values.id().replace("-", "").substring(0, 12);
        String email = "auth-" + suffix + "@example.test";
        fixtureEmails.add(email);
        return new RegisterInput(email, PASSWORD, "auth_" + suffix, "测试用户");
    }

    private UserRow register() {
        RegisterInput in = input();
        accepted(auth.register(in));
        UserRow user = mapper.email(in.email());
        assertNotNull(user);
        return user;
    }

    private List<Mail> mails(String user) {
        return jdbc.query("""
                SELECT id, state, payload FROM background_tasks
                WHERE kind = 'MAIL' AND payload <> ''
                """, (rs, row) -> new Mail(
                rs.getString("id"), rs.getString("state"),
                json.readMap(crypto.decrypt(rs.getString("payload")))))
                .stream().filter(mail -> user.equals(mail.payload().get("user"))).toList();
    }

    private Mail onlyMail(String user) {
        List<Mail> queued = mails(user);
        assertEquals(1, queued.size());
        return queued.get(0);
    }

    private String mailCode(Mail mail) {
        var matcher = CODE.matcher((String) mail.payload().get("body"));
        assertTrue(matcher.find(), "邮件正文应包含六位验证码");
        return matcher.group();
    }

    private String wrongCode(String correct) {
        return "000000".equals(correct) ? "000001" : "000000";
    }

    private String variant(String email) {
        return "  " + email.toUpperCase(Locale.ROOT) + "  ";
    }

    private void accepted(MessageAccepted result) {
        assertEquals(Boolean.TRUE, result.accepted());
        assertEquals(60L, result.resendAfterSeconds());
    }

    private void assertError(ApiException error, int status, String code) {
        assertEquals(status, error.status);
        assertEquals(code, error.code);
    }

    private record Mail(String id, String state, Map<String, Object> payload) {}
}
