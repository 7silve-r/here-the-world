package com.silver.backend.bootstrap;

import com.silver.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @Autowired
    private HttpErrors httpErrors;
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> expectedException(ApiException e, HttpServletRequest r) {
        return httpErrors.response(r, e);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<?> badRequest(Exception e, HttpServletRequest r) {
        for (Throwable cause =  e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException expected) {
                return httpErrors.response(r, expected);
            }
        }
        return httpErrors.response(r,
                new ApiException(400, "BAD_REQUEST", "请求格式不正确"));
    }

    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class
    })
    public ResponseEntity<?> missing(Exception e, HttpServletRequest r) {
        return httpErrors.response(r,
                new ApiException(404, "NOT_FOUND", "接口不存在"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> badMediaType(Exception e, HttpServletRequest r) {
        return httpErrors.response(r,
                new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "请求Content-Type不受支持"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> badRequestMethod(Exception e, HttpServletRequest r) {
        return httpErrors.response(r,
                new ApiException(405, "BAD_REQUEST", "HTTP方法不受支持"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> dataInaccessible(Exception e, HttpServletRequest r) {
        logger.error(
                "Database error requestId={}, type={}",
                r.getAttribute("requestId"),
                e.getClass().getSimpleName()
        );
        return httpErrors.response(r,
                new ApiException(503, "SERVICE_UNAVAILABLE", "数据库暂时不可用"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpectedException(Exception e, HttpServletRequest r) {
        logger.error(
                "Unhandled error requestId={}, type={}",
                r.getAttribute("requestId"),
                e.getClass().getSimpleName()
        );
        return httpErrors.response(r,
                new ApiException(500, "INTERNAL_ERROR", "服务内部错误"));
    }
}
