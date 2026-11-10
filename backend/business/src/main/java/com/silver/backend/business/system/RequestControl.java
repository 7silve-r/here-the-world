package com.silver.backend.business.system;

import com.silver.backend.business.persistence.mapper.ControlMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

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
}
