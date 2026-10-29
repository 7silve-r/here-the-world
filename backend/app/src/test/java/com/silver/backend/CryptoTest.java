package com.silver.backend;

import com.silver.backend.common.ApiException;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private JsonCodec json;
    private Crypto crypto;

    @BeforeEach
    void setUp() {
        json = new JsonCodec();
        ReflectionTestUtils.setField(json, "mapper", JsonMapper.builder().build());
        crypto = newCrypto(SECRET);
    }

    @Test
    @DisplayName("密钥不足 32 字节时拒绝构造")
    void shortKey() {
        assertThrows(IllegalArgumentException.class, () -> new Crypto("a".repeat(31)));
        assertDoesNotThrow(() -> new Crypto("a".repeat(32)));
    }

    @Test
    @DisplayName("密钥长度按 UTF-8 字节计算")
    void utf8Key() {
        assertThrows(IllegalArgumentException.class, () -> new Crypto("中".repeat(10)));
        assertDoesNotThrow(() -> new Crypto("中".repeat(11)));
    }

    @Test
    @DisplayName("随机令牌为 43 位无填充 Base64URL")
    void tokenFormat() {
        String token = crypto.randomToken();

        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, DECODER.decode(token).length);
    }

    @Test
    @DisplayName("验证码固定为六位 ASCII 数字")
    void codeFormat() {
        assertTrue(crypto.code().matches("[0-9]{6}"));
    }

    @Test
    @DisplayName("SHA-256 符合已知摘要")
    void sha256() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                crypto.digest("abc"));
    }

    @Test
    @DisplayName("带密钥摘要受用途和密钥影响")
    void hmacScope() {
        String hash = crypto.keyedDigest("verification", "user-1:000123");

        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, newCrypto(SECRET).keyedDigest("verification", "user-1:000123"));
        assertNotEquals(hash, crypto.keyedDigest("limit", "user-1:000123"));
        assertNotEquals(hash, crypto.keyedDigest("verification", "user-1:000124"));
        assertNotEquals(hash, newCrypto("x".repeat(32)).keyedDigest("verification", "user-1:000123"));
    }

    @Test
    @DisplayName("票据保留载荷并添加到期时间，不修改原 Map")
    void ticketRead() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("upload", "upload-1");
        long before = System.currentTimeMillis();

        String ticket = crypto.ticket("upload", payload, 60_000);
        long after = System.currentTimeMillis();
        Map<String, Object> claims = crypto.open("upload", ticket, "UPLOAD_EXPIRED");
        long expiresAt = ((Number) claims.get("exp")).longValue();

        assertTrue(ticket.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"));
        assertEquals("upload-1", claims.get("upload"));
        assertTrue(expiresAt >= before + 60_000 && expiresAt <= after + 60_000);
        assertEquals(Map.of("upload", "upload-1"), payload);
    }

    @Test
    @DisplayName("票据不能跨用途使用")
    void ticketScope() {
        String ticket = crypto.ticket("upload", Map.of("upload", "upload-1"), 60_000);

        assertApi(400, "ASSET_NOT_FOUND", () -> crypto.open("read", ticket, "ASSET_NOT_FOUND"));
    }

    @Test
    @DisplayName("票据不能使用另一把密钥验证")
    void ticketKey() {
        String ticket = crypto.ticket("upload", Map.of("upload", "upload-1"), 60_000);

        assertApi(400, "INVALID_TICKET",
                () -> newCrypto("x".repeat(32)).open("upload", ticket, "INVALID_TICKET"));
    }

    @Test
    @DisplayName("过期票据被拒绝，不需要等待真实时间")
    void ticketExpiry() {
        String ticket = crypto.ticket("cursor", Map.of("position", 1), -60_000);

        assertApi(400, "INVALID_CURSOR", () -> crypto.open("cursor", ticket, "INVALID_CURSOR"));
    }

    @Test
    @DisplayName("跳过过期检查时仍然验证用途和认证码")
    void oldCursor() {
        String ticket = crypto.ticket("cursor", Map.of("session", "feed-1"), -60_000);

        assertEquals("feed-1", crypto.open("cursor", ticket, "INVALID_CURSOR", false).get("session"));
        assertApi(400, "INVALID_CURSOR",
                () -> crypto.open("read", ticket, "INVALID_CURSOR", false));
        assertApi(400, "INVALID_CURSOR",
                () -> crypto.open("cursor", tamper(ticket, 1), "INVALID_CURSOR", false));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("修改票据载荷或认证码都会失败")
    void ticketTamper(int part) {
        String ticket = crypto.ticket("upload", Map.of("upload", "upload-1"), 60_000);

        assertApi(400, "INVALID_TICKET",
                () -> crypto.open("upload", tamper(ticket, part), "INVALID_TICKET"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "a.", ".b", "a.b.c", "%%.%%"})
    @DisplayName("错误格式的票据返回业务异常")
    void badTicket(String ticket) {
        assertApi(400, "INVALID_TICKET", () -> crypto.open("upload", ticket, "INVALID_TICKET"));
    }

    @Test
    @DisplayName("JWT 可以恢复用户和会话，时间单位为秒")
    void jwtRead() {
        long before = System.currentTimeMillis() / 1000;
        String token = crypto.jwt("user-1", "session-1", 900);
        long after = System.currentTimeMillis() / 1000;

        assertTrue(token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"));
        assertEquals(new Viewer("user-1", "session-1"), crypto.verifyJwt(token));
        Map<String, Object> body = json.readMap(new String(
                DECODER.decode(token.split("\\.", -1)[1]), StandardCharsets.UTF_8));
        long issuedAt = ((Number) body.get("iat")).longValue();
        long expiresAt = ((Number) body.get("exp")).longValue();
        assertTrue(issuedAt >= before && issuedAt <= after);
        assertTrue(expiresAt >= before + 900 && expiresAt <= after + 900);
    }

    @Test
    @DisplayName("过期 JWT 返回专门的过期错误码")
    void jwtExpiry() {
        String token = crypto.jwt("user-1", "session-1", -60);

        assertApi(401, "ACCESS_TOKEN_EXPIRED", () -> crypto.verifyJwt(token));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("修改 JWT 任一部分都会失败")
    void jwtTamper(int part) {
        String token = crypto.jwt("user-1", "session-1", 900);

        assertApi(401, "TOKEN_INVALID", () -> crypto.verifyJwt(tamper(token, part)));
    }

    @Test
    @DisplayName("JWT 不能使用另一把密钥验证")
    void jwtKey() {
        String token = crypto.jwt("user-1", "session-1", 900);

        assertApi(401, "TOKEN_INVALID", () -> newCrypto("x".repeat(32)).verifyJwt(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alg", "typ", "iss", "aud", "sub", "sid", "exp"})
    @DisplayName("即使认证码正确，也拒绝非法 JWT 字段")
    void jwtClaims(String field) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>(Map.of("alg", "HS256", "typ", "JWT"));
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "iss", "here-the-world", "aud", "htw-api",
                "sub", "user-1", "sid", "session-1",
                "exp", System.currentTimeMillis() / 1000 + 900));
        if (header.containsKey(field)) {
            header.put(field, "invalid");
        } else {
            body.put(field, field.equals("iss") || field.equals("aud") ? "invalid" : null);
        }
        String token = signJwt(header, body);

        assertApi(401, "TOKEN_INVALID", () -> crypto.verifyJwt(token));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "a.b", "a.b.", "a.b.c.d", "%%.%%.%%"})
    @DisplayName("错误格式的 JWT 返回业务异常")
    void badJwt(String token) {
        assertApi(401, "TOKEN_INVALID", () -> crypto.verifyJwt(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hello", "你的验证码：000123\n请在十分钟内使用。"})
    @DisplayName("加密内容可以无损解密，包括空串和中文")
    void cipherRead(String text) {
        String encrypted = crypto.encrypt(text);

        assertNotEquals(text, encrypted);
        assertEquals(text, crypto.decrypt(encrypted));
    }

    @Test
    @DisplayName("相同原文每次加密使用新的随机 IV")
    void cipherNonce() {
        String first = crypto.encrypt("验证码：000123");
        String second = crypto.encrypt("验证码：000123");

        assertNotEquals(first, second);
        assertEquals(12, DECODER.decode(first.split("\\.", -1)[0]).length);
        assertEquals("验证码：000123", crypto.decrypt(first));
        assertEquals("验证码：000123", crypto.decrypt(second));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("修改 IV 或密文会导致认证失败")
    void cipherTamper(int part) {
        String encrypted = crypto.encrypt("验证码：000123");

        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tamper(encrypted, part)));
    }

    @Test
    @DisplayName("密文不能使用另一把密钥解密")
    void cipherKey() {
        String encrypted = crypto.encrypt("验证码：000123");

        assertThrows(IllegalStateException.class,
                () -> newCrypto("x".repeat(32)).decrypt(encrypted));
    }

    private Crypto newCrypto(String secret) {
        Crypto value = new Crypto(secret);
        ReflectionTestUtils.setField(value, "json", json);
        return value;
    }

    private void assertApi(int status, String code, Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(status, error.status);
        assertEquals(code, error.code);
    }

    private String tamper(String token, int index) {
        String[] parts = token.split("\\.", -1);
        byte[] bytes = DECODER.decode(parts[index]);
        bytes[0] ^= 1;
        parts[index] = ENCODER.encodeToString(bytes);
        return String.join(".", parts);
    }

    private String signJwt(Map<String, Object> header, Map<String, Object> body) throws Exception {
        String content = ENCODER.encodeToString(json.write(header).getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(json.write(body).getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return content + "." + ENCODER.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
