package com.silver.backend.business;

import com.silver.backend.common.Reply;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

public final class HttpReplies {

    private HttpReplies() {}

    public static ResponseEntity<?> ok(HttpServletRequest request, Object data) {
        return reply(request, Reply.ok(data));
    }

    public static ResponseEntity<?> reply(HttpServletRequest request, Reply reply) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(reply.status());
        if (reply.etag() != null) {
            builder.eTag(reply.etag());
        }
        return builder.body(Values.map(
                "data",  reply.data(),
                "requestId", request.getAttribute("requestId")
                ));
    }
}
