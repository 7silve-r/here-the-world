package com.silver.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silver.backend.bootstrap.ApiSecurityFilter;
import com.silver.backend.bootstrap.GlobalExceptionHandler;
import com.silver.backend.bootstrap.HttpErrors;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.media.MediaService;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.UploadRow;
import com.silver.backend.business.dto.UploadCompleteInput;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.FileStorage;
import com.silver.backend.infrastructure.MediaEngine;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.TaskQueue;
import com.silver.backend.infrastructure.TransactionHelper;
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
import java.util.Map;
import java.util.function.Supplier;

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

    @Test
    void mediaFailure() throws Exception {
        MediaFixture fixture = media(true);
        doThrow(new IOException("decoder failed")).when(fixture.engine()).process(anyString(), anyString(), any());
        try (Events events = new Events(MediaService.class)) {
            fixture.service().process(Map.of("assetId", "asset-test"));
            ILoggingEvent event = events.only();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("assetId=asset-test"));
            assertTrue(event.getFormattedMessage().contains("outputId=asset-test-worker-test"));
            assertEquals("decoder failed", event.getThrowableProxy().getMessage());
        }
    }

    @Test
    void mediaSuccess() throws Exception {
        MediaFixture fixture = media(true);
        try (Events events = new Events(MediaService.class)) {
            fixture.service().process(Map.of("assetId", "asset-test"));
            ILoggingEvent event = events.only();
            assertEquals(Level.INFO, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("completed assetId=asset-test"));
            assertTrue(event.getFormattedMessage().contains("elapsedMs="));
        }
    }

    @Test
    void inspectionFailure() throws Exception {
        MediaService service = new MediaService();
        AssetMapper mapper = mock(AssetMapper.class);
        MediaEngine engine = mock(MediaEngine.class);
        FileStorage storage = mock(FileStorage.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "engine", engine);
        ReflectionTestUtils.setField(service, "storage", storage);
        UploadRow upload = new UploadRow();
        upload.setId("upload-test");
        upload.setAssetId("asset-test");
        upload.setOwnerId("user-test");
        upload.setState("UPLOADED");
        upload.setExpectedSize(4);
        upload.setExpiresAt(Values.now() + 60_000);
        AssetRow asset = new AssetRow();
        asset.setId("asset-test");
        asset.setStorageKey("originals/asset-test");
        when(mapper.upload(upload.getId())).thenReturn(upload);
        when(mapper.asset(asset.getId())).thenReturn(asset);
        when(storage.size(asset.getStorageKey())).thenReturn(4L);
        when(engine.inspect(asset.getStorageKey())).thenThrow(new IOException("probe unavailable"));
        try (Events events = new Events(MediaService.class)) {
            ApiException error = assertThrows(ApiException.class, () -> service.complete(upload.getId(),
                    new Viewer("user-test", "session-test"), new UploadCompleteInput(4L)));
            assertEquals(415, error.status);
            ILoggingEvent event = events.only();
            assertEquals(Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("uploadId=upload-test, assetId=asset-test"));
            assertEquals("probe unavailable", event.getThrowableProxy().getMessage());
        }
    }

    @Test
    void cleanupFailure() throws Exception {
        MediaFixture fixture = media(false);
        String key = "thumbnails/asset-test-worker-test.jpg";
        doThrow(new IOException("disk unavailable")).when(fixture.storage()).delete(key);
        try (Events events = new Events(MediaService.class)) {
            fixture.service().process(Map.of("assetId", "asset-test"));
            ILoggingEvent event = events.list().stream()
                    .filter(e -> e.getFormattedMessage().startsWith("Media output cleanup failed")).findFirst().orElseThrow();
            assertEquals(Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("key=" + key));
            assertEquals("disk unavailable", event.getThrowableProxy().getMessage());
            assertTrue(events.list().stream().noneMatch(e -> e.getLevel() == Level.ERROR));
            verify(fixture.storage()).delete("processed/asset-test-worker-test.mp4");
            verify(fixture.storage()).delete("processed/asset-test-worker-test.mp3");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishResult(boolean failed) {
        WorkService service = new WorkService();
        WorkMapper works = mock(WorkMapper.class);
        AssetMapper assets = mock(AssetMapper.class);
        TaskQueue queue = mock(TaskQueue.class);
        ReflectionTestUtils.setField(service, "tx", transaction());
        ReflectionTestUtils.setField(service, "workMapper", works);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "taskQueue", queue);
        WorkRow work = new WorkRow();
        work.setId("work-test");
        work.setStatus("PROCESSING");
        work.setKind("IMAGE");
        work.setPublishTaskId("publish-test");
        AssetRow asset = new AssetRow();
        asset.setStatus(failed ? "FAILED" : "READY");
        when(works.get("work-test")).thenReturn(work);
        when(works.assets("work-test")).thenReturn(List.of("asset-test"));
        when(assets.asset("asset-test")).thenReturn(asset);
        when(works.update(eq(work), anyLong())).thenReturn(1);
        when(queue.ownsExecution()).thenReturn(true);
        try (Events events = new Events(WorkService.class)) {
            service.finishPublish(Map.of("work", "work-test", "job", "publish-test"));
            ILoggingEvent event = events.only();
            assertEquals(failed ? Level.WARN : Level.INFO, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("workId=work-test"));
            assertTrue(event.getFormattedMessage().contains("jobId=publish-test"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void retryLog(int attempts) {
        QueueFixture fixture = queue("retry", attempts, 1);
        try (Events events = new Events(TaskQueue.class)) {
            fixture.queue().tick();
            ILoggingEvent event = events.only();
            assertEquals(attempts == 7 ? Level.ERROR : Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("taskId=task-test"));
            assertTrue(event.getFormattedMessage().contains("kind=MAIL"));
            assertTrue(event.getFormattedMessage().contains("attempt=" + (attempts + 1)));
            assertEquals("SMTP offline", event.getThrowableProxy().getMessage());
            assertPrivate(event.getFormattedMessage());
        }
        assertThrows(IllegalStateException.class, fixture.queue()::executionId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"waiting", "success"})
    void quietTask(String mode) {
        QueueFixture fixture = queue(mode, 0, 1);
        try (Events events = new Events(TaskQueue.class)) {
            fixture.queue().tick();
            ILoggingEvent event = events.only();
            assertEquals(Level.DEBUG, event.getLevel());
            assertPrivate(event.getFormattedMessage());
        }
    }

    @Test
    void obsoleteTask() {
        QueueFixture fixture = queue("retry", 7, 0);
        try (Events events = new Events(TaskQueue.class)) {
            fixture.queue().tick();
            assertEquals(Level.DEBUG, events.only().getLevel());
            assertTrue(events.only().getFormattedMessage().startsWith("Obsolete task failure ignored"));
        }
    }

    @Test
    void taskPersistence() {
        QueueFixture fixture = queue("retry", 0, 1);
        var failure = new DataAccessResourceFailureException("state unavailable");
        doThrow(failure).when(fixture.mapper()).finish(anyString(), anyString(), anyString(), anyLong(),
                nullable(String.class), anyInt(), anyLong());
        try (Events events = new Events(TaskQueue.class)) {
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class, fixture.queue()::tick));
            ILoggingEvent event = events.only();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("taskId=task-test"));
            assertEquals("state unavailable", event.getThrowableProxy().getMessage());
            assertEquals("SMTP offline", event.getThrowableProxy().getSuppressed()[0].getMessage());
        }
        assertThrows(IllegalStateException.class, fixture.queue()::executionId);
    }

    private QueueFixture queue(String mode, int attempts, int changed) {
        TaskQueue queue = new TaskQueue(7, 30, 100);
        TaskQueue.TaskMapper mapper = mock(TaskQueue.TaskMapper.class);
        Crypto crypto = mock(Crypto.class);
        JsonCodec json = mock(JsonCodec.class);
        ReflectionTestUtils.setField(queue, "mapper", mapper);
        ReflectionTestUtils.setField(queue, "crypto", crypto);
        ReflectionTestUtils.setField(queue, "json", json);
        TaskQueue.Task task = new TaskQueue.Task();
        task.id = "task-test";
        task.kind = "MAIL";
        task.payload = "private-encrypted-payload";
        task.attempts = attempts;
        when(mapper.next(anyLong())).thenReturn(task);
        when(mapper.claim(eq(task.id), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            task.leaseToken = call.getArgument(1);
            return 1;
        });
        when(mapper.get(task.id)).thenReturn(task);
        when(mapper.finish(anyString(), anyString(), anyString(), anyLong(), nullable(String.class), anyInt(), anyLong()))
                .thenReturn(changed);
        when(crypto.decrypt(task.payload)).thenReturn("private-decrypted-payload");
        when(json.readMap(anyString())).thenReturn(Map.of("body", "private-mail-body", "token", "private-reset-token"));
        queue.register(new TaskQueue.Handler() {
            @Override public String kind() { return "MAIL"; }
            @Override public void execute(Map<String, Object> payload) {
                if ("retry".equals(mode)) throw new IllegalStateException("SMTP offline");
                if ("waiting".equals(mode)) throw new TaskQueue.Pending();
            }
        });
        return new QueueFixture(queue, mapper);
    }

    private record QueueFixture(TaskQueue queue, TaskQueue.TaskMapper mapper) {}

    private MediaFixture media(boolean owned) throws IOException {
        MediaService service = new MediaService();
        MediaEngine engine = mock(MediaEngine.class);
        FileStorage storage = mock(FileStorage.class);
        AssetMapper assets = mock(AssetMapper.class);
        TaskQueue queue = mock(TaskQueue.class);
        ReflectionTestUtils.setField(service, "tx", transaction());
        ReflectionTestUtils.setField(service, "engine", engine);
        ReflectionTestUtils.setField(service, "storage", storage);
        ReflectionTestUtils.setField(service, "mapper", assets);
        ReflectionTestUtils.setField(service, "taskQueue", queue);
        AssetRow asset = new AssetRow();
        asset.setId("asset-test");
        asset.setStatus("PROCESSING");
        asset.setStorageKey("originals/asset-test");
        when(assets.asset("asset-test")).thenReturn(asset);
        when(queue.executionId()).thenReturn("worker-test");
        when(queue.ownsExecution()).thenReturn(owned);
        when(engine.inspect(asset.getStorageKey())).thenReturn(new MediaEngine.Info("image/png", "IMAGE", 8, 6, null));
        when(engine.process(anyString(), anyString(), any()))
                .thenReturn(new MediaEngine.Output(asset.getStorageKey(), "thumbnails/asset-test-worker-test.jpg"));
        return new MediaFixture(service, engine, storage);
    }

    @SuppressWarnings("unchecked")
    private TransactionHelper transaction() {
        TransactionHelper tx = mock(TransactionHelper.class);
        when(tx.write(anyString(), any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        return tx;
    }

    private record MediaFixture(MediaService service, MediaEngine engine, FileStorage storage) {}

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
        for (String secret : List.of("private-ticket", "private-access", "private-refresh", "private-password",
                "private-mail-body", "private-reset-token", "private-encrypted-payload", "private-decrypted-payload")) {
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
