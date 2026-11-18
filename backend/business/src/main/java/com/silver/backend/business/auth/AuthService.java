package com.silver.backend.business.auth;

import com.silver.backend.business.dto.Csrf;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    @Autowired
    private Crypto crypto;

    public Csrf csrf(String context) {
        return new Csrf(
                crypto.ticket("csrf", Values.map("context", crypto.digest(context)), 1_800_000),
                Values.time(Values.now() + 1_800_000));
    }
}
