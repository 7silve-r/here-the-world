package com.silver.backend.infrastructure;

import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;


@Component
public class Crypto {
    private final byte[] secret;
    @Autowired
    private JsonCodec json;
    private final SecureRandom random = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    public Crypto(@Value("${htw.secret}") String secret_str) {
        if (secret_str
                .getBytes(StandardCharsets.UTF_8)
                .length < 32) {
            throw new IllegalArgumentException(
                    "HTW_SECRET must contain at least 32 UTF-8 bytes"
            );
        }
        this.secret = secret_str.getBytes(StandardCharsets.UTF_8);
    }

    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public String digest(String text) {
        try{
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8))
            );
        }catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String code() {
        return String.format(
                Locale.ROOT,
                "%06d",
                random.nextInt(1_000_000)
        );
    }

    private byte[] mac(String text) {
        try{
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        }catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String keyedDigest(String purpose, String text) {
        return HexFormat.of().formatHex(mac(purpose + ":" + text));
    }

    public String ticket(String purpose,
                         Map<String, Object> payload,
                         long ttlMillis) {
        var claims = new LinkedHashMap<>(payload);
        claims.put("exp", Values.now() + ttlMillis);
        String body = ENCODER.encodeToString(
                json.write(claims).getBytes(StandardCharsets.UTF_8)
        );
        return body
                + "."
                + ENCODER.encodeToString(mac(purpose + ":" + body));
    }

    public Map<String, Object> open(String purpose,
                                    String token,
                                    String code,
                                    boolean enforceExpiry
    ) {
        try{
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 ||
                !MessageDigest.isEqual(DECODER.decode(parts[1]),
                        mac(purpose + ":" + parts[0]))) {
                throw new IllegalStateException();
            }
            var claims = json.readMap(
                    new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8)
            );
            if (enforceExpiry &&
                    ((Number) claims.get("exp")).longValue() <= Values.now()) {
                throw new IllegalStateException();
            }
            return claims;
        }catch (RuntimeException e) {
            throw new ApiException(400, code, "凭证无效或已过期");
        }
    }

    public Map<String, Object> open(String purpose,
                                    String token,
                                    String code
    ) {
        return open(purpose, token, code, true);
    }

    public String jwt(String userId, String sessionId, long seconds) {
        String header =
                ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                            .getBytes(StandardCharsets.UTF_8));
        String body =
                ENCODER.encodeToString(
                        json.write(
                                Values.map(
                                        "iss",
                                        "here-the-world",
                                        "aud",
                                        "htw-api",
                                        "sub",
                                        userId,
                                        "sid",
                                        sessionId,
                                        "iat",
                                        Values.now() / 1000,
                                        "exp",
                                        Values.now() / 1000 + seconds))
                                .getBytes(StandardCharsets.UTF_8));
        return header + "."
                + body + "."
                + ENCODER.encodeToString(mac(header + "." + body));
    }

    public Viewer verifyJwt(String token) {
        Map<String, Object> claims;
        try {
            String[] p = token.split("\\.", -1);
            if (p.length != 3 ||
                    !MessageDigest.isEqual(DECODER.decode(p[2]),
                            mac(p[0] + "." + p[1]))) {
                throw new IllegalArgumentException();
            }
            var h = json.readMap(new String(DECODER.decode(p[0]), StandardCharsets.UTF_8));
            if (!"HS256".equals(h.get("alg")) || !"JWT".equals(h.get("typ"))) {
                throw new IllegalArgumentException();
            }
            claims = json.readMap(new String(DECODER.decode(p[1]), StandardCharsets.UTF_8));
            if (!"here-the-world".equals(claims.get("iss"))
                    || !"htw-api".equals(claims.get("aud"))
                    || !(claims.get("sub") instanceof String)
                    || !(claims.get("sid") instanceof String)
                    || !(claims.get("exp") instanceof Number)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new ApiException(401, "TOKEN_INVALID", "登录凭证无效");
        }
        if (((Number) claims.get("exp")).longValue() <= Values.now() / 1000) {
            throw new ApiException(401, "ACCESS_TOKEN_EXPIRED", "登录凭证已过期");
        }
        return new Viewer((String) claims.get("sub"), (String) claims.get("sid"));
    }


    private SecretKeySpec key() {
        return new SecretKeySpec(
                HexFormat.of().parseHex(keyedDigest("encryption", "tasks")),
                "AES"
        );
    }

    public String encrypt(String text) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return ENCODER.encodeToString(iv)
                    + "."
                    + ENCODER.encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String text) {
        try {
            String[] p = text.split("\\.");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, DECODER.decode(p[0])));
            return new String(c.doFinal(DECODER.decode(p[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt task payload", e);
        }
    }

}
