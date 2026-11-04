package com.silver.backend.bootstrap;

import com.silver.backend.business.dto.ApiError;
import com.silver.backend.common.ApiException;
import com.silver.backend.infrastructure.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class HttpErrors {
    @Autowired
    private JsonCodec json;

    public ApiError body(HttpServletRequest request,
                         ApiException exception) {
        return new ApiError(exception.code,
                exception.getMessage(),
                (String) request.getAttribute("requestId"),
                exception.details);
    }

    public ResponseEntity<ApiError> response(HttpServletRequest request,
                                             ApiException exception) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.status);
        if (exception.retryAfter != null) {
            builder.header("Retry-After", exception.retryAfter.toString());
        }
        return builder.body(body(request, exception));
    }

    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      ApiException exception) throws IOException {
        response.setStatus(exception.status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (exception.retryAfter != null) {
            response.setHeader("Retry-After", exception.retryAfter.toString());
        }
        response.getWriter().write(json.write(body(request, exception)));
    }
}
