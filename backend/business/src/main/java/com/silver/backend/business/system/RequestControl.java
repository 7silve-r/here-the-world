package com.silver.backend.business.system;

import com.silver.backend.business.persistence.IdempotencyRow;
import com.silver.backend.business.persistence.mapper.ControlMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RequestControl {
    @Autowired
    TransactionHelper tx;
    @Autowired
    ControlMapper mapper;
    @Autowired
    Crypto crypto;
    @Autowired
    JsonCodec json;

    public void limit(String scope, String identity, int max, int seconds) {
        String key = crypto.keyedDigest("limit", scope + ":" + identity);
        boolean rejected = tx.write(
                "00:rate" + key,
                () -> {
                    Integer hits = mapper.rate(key, Values.now());
                    if (hits == null) {
                        mapper.deleteRate(key);
                        mapper.insertRate(key, Values.now() + seconds * 1000L);
                        return false;
                    }
                    if (hits >= max) {
                        return true;
                    }
                    mapper.incrementRate(key);
                    return false;
                });
        if (rejected) {
            throw new ApiException(429, "RATE_LIMITED", "请求过于频繁", List.of(), seconds);
        }
    }

    public Reply idempotency(
            String user, String path, String key, Map<String, Object> input, Supplier<Reply> action) {
        try {
            if (key == null || !UUID.fromString(key).toString().equalsIgnoreCase(key)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "BAD_REQUEST", "需要UUID格式的Idempotency-key");
        }
        String id = crypto.digest(user + "\nPOST\n" + path + "\n" + key);
        String hash = crypto.digest(json.write(json.canonical(input)));
        String owner = Values.id();
        String lock = "01:request" + id;
        IdempotencyRow running = mapper.idem(id);
        if (running != null
            && running.getExpiresAt() > Values.now()
            && running.getResponseJson() == null
            && running.getLeaseUntil() > Values.now()) {
            if (!hash.equals(running.getBodyHash())) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于不同请求");
            }
            throw new ApiException(409, "REQUEST_IN_PROGRESS", "请求正在处理", List.of(), 2);
        }
        Reply cached =
                tx.write(
                        lock,
                        () -> {
                            IdempotencyRow old = mapper.lockedIdem(id);
                            if (old != null && old.getExpiresAt() <= Values.now()) {
                                mapper.deleteIdem(id);
                                old = null;
                            }
                            if (old != null) {
                                if (!hash.equals(old.getBodyHash())) {
                                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于不同请求");
                                }
                                if (old.getResponseJson() != null) {
                                    return json.read(crypto.decrypt(old.getResponseJson()), Reply.class);
                                }
                                if (old.getLeaseUntil() > Values.now()) {
                                    throw new ApiException(409, "REQUEST_IN_PROGRESS", "请求正在处理",  List.of(), 2);
                                }
                                mapper.deleteIdem(id);
                            }
                            IdempotencyRow row = new IdempotencyRow();
                            row.setId(id);
                            row.setBodyHash(hash);
                            row.setExpiresAt(Values.now() + 86_400_000);
                            row.setLeaseUntil(Values.now() + 120_000);
                            row.setLeaseToken(owner);
                            mapper.insertIdem(row);
                            return null;
                        }
                );
        if (cached != null) {
            return cached;
        }
        try{
            return tx.write(
                    lock,
                    () -> {
                        IdempotencyRow claim = mapper.lockedIdem(id);
                        if (claim == null || !owner.equals(claim.getLeaseToken())) {
                            throw new ApiException(409, "REQUEST_IN_PROGRESS", "请求已由其他执行者接管",  List.of(), 2);
                        }
                        Reply result = action.get();
                        if (mapper.complete(id, owner, crypto.encrypt(json.write(result))) != 1) {
                            throw new IllegalStateException("Lost idempotency ownership");
                        }
                        return result;
                    }
            );
        } catch (RuntimeException e) {
            tx.run(lock, () -> mapper.abandon(id, owner));
            throw e;
        }
    }
}
