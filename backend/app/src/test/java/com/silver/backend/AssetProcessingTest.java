package com.silver.backend;

import com.silver.backend.business.dto.UploadInput;
import com.silver.backend.business.dto.UploadTicket;
import com.silver.backend.business.media.MediaService;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.FileStorage;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.MediaEngine;
import com.silver.backend.infrastructure.TaskQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
class AssetProcessingTest extends BackendTestSupport {
    private static final Path ROOT = Path.of("target", "asset-test-" + Values.id()).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("htw.storage-root", ROOT::toString);
    }

    @Autowired private MockMvc mvc;
    @Autowired private MediaService service;
    @Autowired private TaskQueue queue;
    @Autowired private AuthMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private FileStorage storage;
    @MockitoSpyBean private AssetMapper assets;
    @MockitoSpyBean private MediaEngine engine;
    @MockitoSpyBean private TaskQueue.TaskMapper tasks;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();

    @BeforeEach
    void clearTasks() {
        jdbc.update("DELETE FROM background_tasks");
    }

    @AfterEach
    void cleanup() throws IOException {
        jdbc.update("DELETE FROM background_tasks");
        for (String work : workIds) {
            jdbc.update("DELETE FROM work_assets WHERE work_id=?", work);
            jdbc.update("DELETE FROM works WHERE id=?", work);
        }
        for (String user : userIds) {
            jdbc.update("UPDATE users SET avatar_asset_id=NULL WHERE id=?", user);
            jdbc.update("DELETE FROM uploads WHERE owner_id=?", user);
            jdbc.update("DELETE FROM assets WHERE owner_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
        clearFiles(false);
    }

    @AfterAll
    static void removeRoot() throws IOException {
        clearFiles(true);
    }

    private static void clearFiles(boolean root) throws IOException {
        if (!Files.exists(ROOT)) return;
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (root || !path.equals(ROOT)) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void completes() throws Exception {
        Upload upload = uploaded(account());
        long before = Values.now();
        Map<String, Object> result = data(complete(upload), 202);
        AssetRow a = asset(upload);
        assertEquals(upload.ticket().assetId(), result.get("id"));
        assertEquals("PROCESSING", result.get("status"));
        assertNull(result.get("url"));
        assertEquals("PROCESSING", a.getStatus());
        assertEquals("image/png", a.getMimeType());
        assertEquals(8, a.getWidth());
        assertEquals(6, a.getHeight());
        assertEquals(upload.bytes().length, a.getSizeBytes());
        assertNull(a.getPlaybackKey());
        assertNull(a.getThumbnailKey());
        var u = assets.upload(upload.ticket().uploadId());
        assertEquals("COMPLETED", u.getState());
        assertTrue(u.getFinishedAt() >= before && u.getFinishedAt() <= Values.now());
        TaskQueue.Task task = task();
        assertEquals("PENDING", task.state);
        assertEquals(Map.of("assetId", a.getId()), json.readMap(crypto.decrypt(task.payload)));
        assertFalse(task.payload.contains(a.getId()));
        verify(engine, never()).process(anyString(), anyString(), any());
    }

    @Test
    void processesImage() throws Exception {
        Upload upload = prepared();
        String taskId = task().id;
        queue.tick();
        AssetRow a = asset(upload);
        assertEquals("READY", a.getStatus());
        assertEquals(a.getStorageKey(), a.getPlaybackKey());
        assertTrue(Files.exists(storage.path(a.getThumbnailKey())));
        BufferedImage thumb = ImageIO.read(storage.path(a.getThumbnailKey()).toFile());
        assertEquals(8, thumb.getWidth());
        assertEquals(6, thumb.getHeight());
        assertArrayEquals(upload.bytes(), Files.readAllBytes(storage.path(a.getStorageKey())));
        assertNull(a.getFailureCode());
        assertEquals("DONE", tasks.get(taskId).state);
        assertEquals("", tasks.get(taskId).payload);
        Map<String, Object> result = data(getAsset(upload, upload.owner()), 200);
        assertTrue(result.get("url").toString().contains("/media/private/assets/" + a.getId() + "/file"));
        assertTrue(result.get("thumbnailUrl").toString().endsWith("/thumbnail"));
    }

    @ParameterizedTest
    @CsvSource({"jpeg,image/jpeg", "gif,image/gif"})
    void realFormats(String format, String mime) throws Exception {
        Upload upload = uploaded(account(), "WORK_MEDIA", "image/png", image(format));
        data(complete(upload), 202);
        assertEquals(mime, asset(upload).getMimeType());
        queue.tick();
        assertEquals("READY", asset(upload).getStatus());
    }

    @Test
    void repeatComplete() throws Exception {
        Upload upload = prepared();
        Long finished = assets.upload(upload.ticket().uploadId()).getFinishedAt();
        jdbc.update("UPDATE uploads SET expires_at=0 WHERE id=?", upload.ticket().uploadId());
        data(complete(upload), 202);
        assertEquals(1, taskCount());
        assertEquals(finished, assets.upload(upload.ticket().uploadId()).getFinishedAt());
        verify(engine, times(1)).inspect(asset(upload).getStorageKey());
        queue.tick();
        assertEquals("READY", data(complete(upload), 202).get("status"));
        assertEquals(1, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "RECEIVING", "CANCELLED", "EXPIRED"})
    void rejectsState(String state) throws Exception {
        Upload upload = uploaded(account());
        jdbc.update("UPDATE uploads SET state=? WHERE id=?", state, upload.ticket().uploadId());
        error(complete(upload), 422, "VALIDATION_FAILED");
        assertEquals(0, taskCount());
    }

    @Test
    void expired() throws Exception {
        Upload upload = uploaded(account());
        jdbc.update("UPDATE uploads SET expires_at=? WHERE id=?", Values.now() - 1, upload.ticket().uploadId());
        error(complete(upload), 410, "UPLOAD_EXPIRED");
        assertEquals(0, taskCount());
    }

    @Test
    void completeAccess() throws Exception {
        Upload upload = uploaded(account());
        error(perform(post(path(upload)).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("sizeBytes", upload.bytes().length)))), 401, "AUTH_REQUIRED");
        error(perform(auth(post(path(upload)), account()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("sizeBytes", upload.bytes().length)))), 404, "UPLOAD_NOT_FOUND");
        error(perform(auth(post("/api/v1/uploads/unknown/complete"), upload.owner())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sizeBytes\":1}")), 404, "UPLOAD_NOT_FOUND");
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"sizeBytes\":null}", "{\"sizeBytes\":0}", "{\"sizeBytes\":1.5}",
            "{\"sizeBytes\":1,\"ownerId\":\"forged\"}"})
    void invalidInput(String input) throws Exception {
        Upload upload = uploaded(account());
        error(perform(auth(post(path(upload)), upload.owner()).contentType(MediaType.APPLICATION_JSON)
                .content(input)), 422, "VALIDATION_FAILED");
        assertEquals(0, taskCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sizeMismatch(boolean disk) throws Exception {
        Upload upload = uploaded(account());
        if (disk) Files.write(storage.path(asset(upload).getStorageKey()), new byte[] {1, 2});
        long declared = upload.bytes().length + (disk ? 0 : 1);
        error(perform(auth(post(path(upload)), upload.owner()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("sizeBytes", declared)))), 422, "VALIDATION_FAILED");
        assertEquals("UPLOADED", asset(upload).getStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void badMedia() throws Exception {
        Upload upload = uploaded(account());
        String key = asset(upload).getStorageKey();
        doThrow(new IOException("decoder failed")).when(engine).inspect(key);
        error(complete(upload), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertEquals("UPLOADED", asset(upload).getStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void wrongCategory() throws Exception {
        Upload upload = uploaded(account());
        String key = asset(upload).getStorageKey();
        doReturn(new MediaEngine.Info("video/mp4", "VIDEO", 8, 6, 1000L))
                .when(engine).inspect(key);
        error(complete(upload), 422, "ASSET_TYPE_MISMATCH");
        assertEquals(0, taskCount());
    }

    @Test
    void wrongPurpose() throws Exception {
        Upload upload = uploaded(account(), "AVATAR", "image/png", image("png"));
        String key = asset(upload).getStorageKey();
        doReturn(new MediaEngine.Info("image/gif", "IMAGE", 8, 6, null))
                .when(engine).inspect(key);
        error(complete(upload), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertEquals(0, taskCount());
    }

    @Test
    void enqueueRollback() throws Exception {
        Upload upload = uploaded(account());
        doThrow(new DataAccessResourceFailureException("queue unavailable"))
                .when(tasks).insert(any(TaskQueue.Task.class));
        error(complete(upload), 503, "SERVICE_UNAVAILABLE");
        assertEquals("UPLOADED", asset(upload).getStatus());
        assertNull(asset(upload).getWidth());
        assertEquals("UPLOADED", assets.upload(upload.ticket().uploadId()).getState());
        assertNull(assets.upload(upload.ticket().uploadId()).getFinishedAt());
        assertEquals(0, taskCount());
        reset(tasks);
        data(complete(upload), 202);
        assertEquals(1, taskCount());
    }

    @Test
    void cancelDuringProbe() throws Exception {
        Upload upload = uploaded(account());
        try (Gate gate = new Gate()) {
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                Object info = call.callRealMethod();
                gate.waitHere();
                return info;
            }).when(engine).inspect(anyString());
            var result = gate.pool.submit(() -> complete(upload));
            gate.await();
            service.cancel(upload.ticket().uploadId(), upload.owner().viewer());
            gate.release();
            error(result.get(10, TimeUnit.SECONDS), 410, "UPLOAD_EXPIRED");
        }
        assertEquals("CANCELLED", assets.upload(upload.ticket().uploadId()).getState());
        assertEquals("FAILED", asset(upload).getStatus());
        assertEquals(0, taskCount());
    }

    @Test
    void concurrentComplete() throws Exception {
        Upload upload = uploaded(account());
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(call -> {
            Object info = call.callRealMethod();
            barrier.await(5, TimeUnit.SECONDS);
            return info;
        }).when(engine).inspect(anyString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> complete(upload));
            var second = pool.submit(() -> complete(upload));
            data(first.get(10, TimeUnit.SECONDS), 202);
            data(second.get(10, TimeUnit.SECONDS), 202);
            assertEquals(1, taskCount());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void processorFails() throws Exception {
        Upload upload = prepared();
        doThrow(new IOException("transcode failed")).when(engine).process(anyString(), anyString(), any());
        queue.tick();
        AssetRow a = asset(upload);
        assertEquals("FAILED", a.getStatus());
        assertEquals("MEDIA_PROCESSING_FAILED", a.getFailureCode());
        assertNull(a.getPlaybackKey());
        assertNull(a.getThumbnailKey());
        assertEquals("DONE", task().state);
        assertEquals("MEDIA_PROCESSING_FAILED", data(getAsset(upload, upload.owner()), 200).get("failureCode"));
    }

    @Test
    void resultRollback() throws Exception {
        Upload upload = prepared();
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        doAnswer(call -> {
            AssetRow a = call.getArgument(0);
            if ("READY".equals(a.getStatus())) throw new DataAccessResourceFailureException("result failed");
            actual.updateAsset(a);
            return null;
        }).when(assets).updateAsset(any(AssetRow.class));
        queue.tick();
        assertEquals("FAILED", asset(upload).getStatus());
        assertNull(asset(upload).getPlaybackKey());
        assertNull(asset(upload).getThumbnailKey());
        assertEquals(List.of(asset(upload).getStorageKey()), files());
    }

    @Test
    void recoveryCleanup() throws Exception {
        Upload upload = prepared();
        doThrow(new DataAccessResourceFailureException("result unavailable"))
                .when(assets).updateAsset(any(AssetRow.class));
        queue.tick();
        assertEquals("PROCESSING", asset(upload).getStatus());
        assertEquals("PENDING", task().state);
        assertEquals(List.of(asset(upload).getStorageKey()), files());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leaseTakeover(boolean oldFails) throws Exception {
        Upload upload = prepared();
        AtomicInteger calls = new AtomicInteger();
        List<String> outputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (Gate gate = new Gate()) {
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "转码不能持有事务");
                outputs.add(call.getArgument(0));
                if (calls.incrementAndGet() == 1) {
                    gate.waitHere();
                    if (oldFails) throw new IOException("old worker failed");
                }
                return call.callRealMethod();
            }).when(engine).process(anyString(), anyString(), any());
            var first = gate.pool.submit(queue::tick);
            gate.await();
            String oldToken = task().leaseToken;
            jdbc.update("UPDATE background_tasks SET lease_until=0 WHERE id=?", task().id);
            queue.tick();
            AssetRow accepted = asset(upload);
            assertEquals("READY", accepted.getStatus());
            assertFalse(accepted.getThumbnailKey().contains(oldToken));
            gate.release();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(accepted.getThumbnailKey(), asset(upload).getThumbnailKey());
            assertEquals("READY", asset(upload).getStatus());
            assertEquals("DONE", task().state);
            assertEquals(2, outputs.size());
            assertNotEquals(outputs.get(0), outputs.get(1));
            assertFalse(Files.exists(storage.path("thumbnails/" + outputs.get(0) + ".jpg")));
            assertTrue(Files.exists(storage.path(accepted.getThumbnailKey())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "DELETED"})
    void noRevival(String state) throws Exception {
        Upload upload = prepared();
        try (Gate gate = new Gate()) {
            doAnswer(call -> {
                gate.waitHere();
                return call.callRealMethod();
            }).when(engine).process(anyString(), anyString(), any());
            var running = gate.pool.submit(queue::tick);
            gate.await();
            if ("DELETED".equals(state)) {
                jdbc.update("DELETE FROM uploads WHERE id=?", upload.ticket().uploadId());
                jdbc.update("DELETE FROM assets WHERE id=?", upload.ticket().assetId());
            } else {
                jdbc.update("UPDATE assets SET status='FAILED',failure_code='ASSET_EXPIRED',cleaned_at=? WHERE id=?",
                        Values.now(), upload.ticket().assetId());
            }
            gate.release();
            running.get(10, TimeUnit.SECONDS);
        }
        if ("DELETED".equals(state)) assertNull(asset(upload));
        else {
            assertEquals("FAILED", asset(upload).getStatus());
            assertEquals("ASSET_EXPIRED", asset(upload).getFailureCode());
            assertNotNull(asset(upload).getCleanedAt());
        }
        assertEquals(List.of("originals/" + upload.ticket().assetId()), files());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "UPLOADED", "READY", "FAILED", "MISSING"})
    void skipsStaleTask(String state) throws Exception {
        Upload upload = prepared();
        if ("MISSING".equals(state)) {
            jdbc.update("DELETE FROM uploads WHERE id=?", upload.ticket().uploadId());
            jdbc.update("DELETE FROM assets WHERE id=?", upload.ticket().assetId());
        } else jdbc.update("UPDATE assets SET status=? WHERE id=?", state, upload.ticket().assetId());
        clearInvocations(engine);
        queue.tick();
        verifyNoInteractions(engine);
        assertEquals("DONE", task().state);
    }

    @Test
    void privateRead() throws Exception {
        Upload upload = prepared();
        assertEquals("PROCESSING", data(getAsset(upload, upload.owner()), 200).get("status"));
        error(getAsset(upload, null), 404, "ASSET_NOT_FOUND");
        error(getAsset(upload, account()), 404, "ASSET_NOT_FOUND");
        error(perform(get("/api/v1/assets/unknown")), 404, "ASSET_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar", "cover", "attachment"})
    void publicRead(String reference) throws Exception {
        Upload upload = prepared();
        queue.tick();
        String asset = upload.ticket().assetId();
        if ("avatar".equals(reference)) {
            jdbc.update("UPDATE users SET avatar_asset_id=? WHERE id=?", asset, upload.owner().user());
        } else {
            String work = Values.id();
            workIds.add(work);
            jdbc.update("""
                    INSERT INTO works(id,owner_id,kind,status,version,title,body,cover_asset_id,created_at,updated_at)
                    VALUES(?,?,'IMAGE','PUBLISHED',1,'media','',?, ?,?)
                    """, work, upload.owner().user(), "cover".equals(reference) ? asset : null, Values.now(), Values.now());
            if ("attachment".equals(reference)) {
                jdbc.update("INSERT INTO work_assets(work_id,asset_id,position) VALUES(?,?,0)", work, asset);
            }
        }
        Map<String, Object> result = data(getAsset(upload, null), 200);
        assertTrue(result.get("url").toString().contains("/media/public/assets/"));
        if (!"avatar".equals(reference)) {
            jdbc.update("UPDATE works SET status='DRAFT' WHERE id=?", workIds.get(0));
            error(getAsset(upload, null), 404, "ASSET_NOT_FOUND");
        }
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users(id,email,username,password_hash,display_name,bio,email_verified,theme,motion,auto_play_media,created_at)
                VALUES(?,?,?,'private-password-hash','媒体用户','',TRUE,'SYSTEM','SYSTEM',TRUE,?)
                """, user, user + "@example.test", "asset_" + user.substring(0, 8), Values.now());
        userIds.add(user);
        SessionRow session = new SessionRow();
        session.setId(Values.id());
        session.setUserId(user);
        session.setRefreshHash(crypto.digest(crypto.randomToken()));
        session.setCreatedAt(Values.now());
        session.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(session);
        return new Account(user, session.getId(), crypto.jwt(user, session.getId(), 900));
    }

    private Upload uploaded(Account owner) throws Exception {
        return uploaded(owner, "WORK_MEDIA", "image/png", image("png"));
    }

    private Upload uploaded(Account owner, String purpose, String mime, byte[] bytes) throws Exception {
        UploadTicket ticket = service.create(owner.viewer(), new UploadInput(purpose, "image.png", mime, (long) bytes.length));
        String token = URLDecoder.decode(URI.create(ticket.uploadUrl()).getRawQuery().substring(7), StandardCharsets.UTF_8);
        service.transfer(token, new ByteArrayInputStream(bytes));
        return new Upload(owner, ticket, bytes);
    }

    private Upload prepared() throws Exception {
        Upload upload = uploaded(account());
        data(complete(upload), 202);
        return upload;
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00ff00);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        return bytes.toByteArray();
    }

    private String path(Upload upload) {
        return "/api/v1/uploads/" + upload.ticket().uploadId() + "/complete";
    }

    private MockHttpServletResponse complete(Upload upload) throws Exception {
        return perform(auth(post(path(upload)), upload.owner()).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("sizeBytes", upload.bytes().length))));
    }

    private MockHttpServletResponse getAsset(Upload upload, Account viewer) throws Exception {
        var request = get("/api/v1/assets/" + upload.ticket().assetId());
        return perform(viewer == null ? request : auth(request, viewer));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Account account) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + account.access());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-ID", "asset-" + Values.id())).andReturn().getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return (Map<String, Object>) json.readMap(response.getContentAsString()).get("data");
    }

    private void error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertEquals(code, json.readMap(response.getContentAsString()).get("code"));
    }

    private AssetRow asset(Upload upload) {
        return assets.asset(upload.ticket().assetId());
    }

    private int taskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM background_tasks", Integer.class);
    }

    private TaskQueue.Task task() {
        List<String> ids = jdbc.queryForList("SELECT id FROM background_tasks", String.class);
        assertEquals(1, ids.size());
        return tasks.get(ids.get(0));
    }

    private List<String> files() throws IOException {
        try (var paths = Files.walk(ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .map(p -> ROOT.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private static class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final java.util.concurrent.ExecutorService pool = Executors.newSingleThreadExecutor();

        void waitHere() throws InterruptedException {
            entered.countDown();
            assertTrue(proceed.await(10, TimeUnit.SECONDS));
        }

        void await() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }

        void release() {
            proceed.countDown();
        }

        @Override
        public void close() throws InterruptedException {
            release();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private record Account(String user, String session, String access) {
        Viewer viewer() { return new Viewer(user, session); }
    }
    private record Upload(Account owner, UploadTicket ticket, byte[] bytes) {}
}
