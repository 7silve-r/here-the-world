package com.silver.backend.business.auth;

import com.silver.backend.business.dto.Csrf;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class AuthService {

    @Autowired
    private Crypto crypto;
    @Autowired
    private AuthMapper mapper;

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
}
