package com.silver.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silver.backend.bootstrap.ApiSecurityFilter;
import com.silver.backend.bootstrap.GlobalExceptionHandler;
import com.silver.backend.bootstrap.HttpErrors;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.common.ApiException;
import com.silver.backend.infrastructure.Crypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoggingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestFailure(boolean database) {
        var handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "httpErrors", new HttpErrors());
        var request = request();
        RuntimeException failure = database ? new DataAccessResourceFailureException("database offline")
                : new IllegalStateException("unexpected failure");
        failure.addSuppressed(new IOException("compensation failed"));
        try (Events events = new Events(GlobalExceptionHandler.class)) {
            var response = database ? handler.dataInaccessible(failure, request)
                    : handler.unexpectedException(failure, request);
            assertEquals(database ? 503 : 500, response.getStatusCode().value());
            ILoggingEvent event = events.only();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("requestId=request-test"));
            assertTrue(event.getFormattedMessage().contains("method=GET"));
            assertTrue(event.getFormattedMessage().contains("path=/api/v1/assets/example"));
            assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
            assertEquals("compensation failed", event.getThrowableProxy().getSuppressed()[0].getMessage());
            assertPrivate(event.getFormattedMessage());
        }
    }

    @Test
    void expectedError() {
        var handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "httpErrors", new HttpErrors());
        try (Events events = new Events(GlobalExceptionHandler.class)) {
            assertEquals(422, handler.expectedException(
                    ApiException.invalid("email", "INVALID_FORMAT", "邮箱格式不正确"), request())
                    .getStatusCode().value());
            assertTrue(events.list().isEmpty());
        }
    }

    @Test
    void securityFailure() throws Exception {
        HttpErrors errors = mock(HttpErrors.class);
        var filter = new ApiSecurityFilter(mock(AuthService.class), mock(Crypto.class), errors,
                mock(RequestControl.class), "http://localhost:5173");
        var request = request();
        request.removeHeader("Authorization");
        try (Events events = new Events(ApiSecurityFilter.class)) {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new DataAccessResourceFailureException("database offline");
            });
            ILoggingEvent event = events.only();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("requestId=request-test"));
            assertNotNull(event.getThrowableProxy());
            assertPrivate(event.getFormattedMessage());
            verify(errors).write(eq(request), any(), argThat(e -> e.status == 503));
        }
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/assets/example");
        request.setAttribute("requestId", "request-test");
        request.setQueryString("ticket=private-ticket");
        request.addHeader("Authorization", "Bearer private-access");
        request.addHeader("Cookie", "refresh=private-refresh");
        request.setContent("{\"password\":\"private-password\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static void assertPrivate(String message) {
        for (String secret : List.of("private-ticket", "private-access", "private-refresh", "private-password")) {
            assertFalse(message.contains(secret));
        }
    }

    private static final class Events implements AutoCloseable {
        private final Logger logger;
        private final Level previous;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Events(Class<?> owner) {
            logger = (Logger) LoggerFactory.getLogger(owner);
            previous = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            appender.start();
            logger.addAppender(appender);
        }

        List<ILoggingEvent> list() { return appender.list; }

        ILoggingEvent only() {
            assertEquals(1, list().size());
            return list().get(0);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
