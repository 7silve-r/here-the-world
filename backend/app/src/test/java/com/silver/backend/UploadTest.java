package com.silver.backend;

import com.silver.backend.business.dto.UploadTicket;
import com.silver.backend.business.media.MediaService;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UploadRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.ControlMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.FileStorage;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class UploadTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/uploads";
    private static final String BINARY = "/api/v1/media/upload";
    private static final byte[] PAYLOAD = {1, 2, 3, 4};
    private static final Path MEDIA_ROOT = Path.of("target", "upload-test-" + Values.id()).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("htw.storage-root", MEDIA_ROOT::toString);
    }

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private ControlMapper control;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private MediaService service;
    @MockitoSpyBean private AssetMapper assets;
    @MockitoSpyBean private FileStorage storage;

    private final List<String> userIds = new ArrayList<>();
    private final Set<String> idemIds = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanFixtures() throws IOException {
        for (String id : idemIds) jdbc.update("DELETE FROM idempotency WHERE id = ?", id);
        for (String user : userIds) {
            jdbc.update("DELETE FROM uploads WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM assets WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
        clearMedia(false);
    }

    @AfterAll
    static void removeDirectory() throws IOException {
        clearMedia(true);
    }

    private static void clearMedia(boolean includeRoot) throws IOException {
        if (!Files.exists(MEDIA_ROOT)) return;
        try (var paths = Files.walk(MEDIA_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (includeRoot || !path.equals(MEDIA_ROOT)) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void creates() throws Exception {
        Account owner = account();
        long before = Values.now();
        Ticket upload = upload(owner);
        UploadTicket result = upload.ticket();
        AssetRow asset = assets.asset(result.assetId());
        UploadRow row = assets.upload(result.uploadId());

        assertEquals(owner.user(), asset.getOwnerId());
        assertEquals("WORK_MEDIA", asset.getPurpose());
        assertEquals("IMAGE", asset.getMediaType());
        assertEquals("CREATED", asset.getStatus());
        assertEquals("image/png", asset.getMimeType());
        assertEquals(PAYLOAD.length, asset.getSizeBytes());
        assertTrue(asset.getCreatedAt() >= before && asset.getCreatedAt() <= Values.now());
        assertNull(asset.getCleanedAt());
        assertNull(asset.getPlaybackKey());
        assertNull(asset.getThumbnailKey());
        assertNull(asset.getWidth());
        assertNull(asset.getHeight());
        assertNull(asset.getDurationMs());
        assertNull(asset.getFailureCode());
        assertEquals("originals/" + asset.getId(), asset.getStorageKey());
        assertEquals(asset.getId(), row.getAssetId());
        assertEquals(owner.user(), row.getOwnerId());
        assertEquals("CREATED", row.getState());
        assertEquals(PAYLOAD.length, row.getExpectedSize());
        assertTrue(row.getCreatedAt() >= before && row.getCreatedAt() <= Values.now());
        assertTrue(row.getExpiresAt() >= before + 900_000 && row.getExpiresAt() <= Values.now() + 900_000);
        assertNull(row.getFinishedAt());
        assertEquals("PUT", result.method());
        assertEquals(Map.of("Content-Type", "image/png"), result.headers());
        assertEquals(20L * 1024 * 1024, result.maxBytes());
        assertEquals(Values.time(row.getExpiresAt()), result.expiresAt());
        assertEquals(BINARY, URI.create(result.uploadUrl()).getPath());
        assertEquals(row.getId(), crypto.open("upload", upload.token(), "INVALID").get("upload"));
        assertEquals(List.of(), files());
    }

    @ParameterizedTest
    @CsvSource({"WORK_MEDIA,image/jpeg,IMAGE", "WORK_MEDIA,image/png,IMAGE", "WORK_MEDIA,image/webp,IMAGE",
            "WORK_MEDIA,image/gif,IMAGE", "WORK_MEDIA,video/mp4,VIDEO", "WORK_MEDIA,video/webm,VIDEO",
            "WORK_MEDIA,audio/mpeg,AUDIO", "WORK_MEDIA,audio/mp4,AUDIO", "WORK_MEDIA,audio/wav,AUDIO",
            "WORK_MEDIA,audio/x-wav,AUDIO", "WORK_MEDIA,audio/ogg,AUDIO", "WORK_MEDIA,audio/flac,AUDIO",
            "WORK_COVER,image/jpeg,IMAGE", "WORK_COVER,image/png,IMAGE", "WORK_COVER,image/webp,IMAGE",
            "AVATAR,image/jpeg,IMAGE", "AVATAR,image/png,IMAGE", "AVATAR,image/webp,IMAGE"})
    void supportedTypes(String purpose, String mime, String type) throws Exception {
        Ticket upload = upload(account(), purpose, mime, 4);
        AssetRow asset = assets.asset(upload.ticket().assetId());
        assertEquals(purpose, asset.getPurpose());
        assertEquals(mime, asset.getMimeType());
        assertEquals(type, asset.getMediaType());
        assertEquals(Map.of("Content-Type", mime), upload.ticket().headers());
    }

    @ParameterizedTest
    @CsvSource({"WORK_MEDIA,image/png,20971520", "WORK_MEDIA,video/mp4,524288000",
            "WORK_MEDIA,audio/mpeg,104857600", "WORK_COVER,image/png,10485760", "AVATAR,image/png,5242880"})
    void sizeBoundary(String purpose, String mime, long maximum) throws Exception {
        Account owner = account();
        Ticket accepted = upload(owner, purpose, mime, maximum);
        assertEquals(maximum, accepted.ticket().maxBytes());
        assertError(create(owner, Values.id(), body(purpose, mime, maximum + 1)), 413, "FILE_TOO_LARGE");
        assertEquals(1, count("assets", owner));
        assertEquals(1, count("uploads", owner));
    }

    @ParameterizedTest
    @CsvSource({"WORK_COVER,image/gif", "AVATAR,video/mp4", "WORK_COVER,audio/mpeg", "WORK_MEDIA,text/plain"})
    void unsupportedType(String purpose, String mime) throws Exception {
        Account owner = account();
        assertError(create(owner, Values.id(), body(purpose, mime, 4)), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertEquals(0, count("assets", owner));
        assertEquals(0, count("uploads", owner));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidInput(Map<String, Object> input, String field, String reason) throws Exception {
        Account owner = account();
        String key = Values.id();
        MockHttpServletResponse response = create(owner, key, input);
        assertError(response, 422, "VALIDATION_FAILED");
        List<?> details = (List<?>) json.readMap(response.getContentAsString()).get("details");
        assertEquals(field, object(details.get(0)).get("field"));
        assertEquals(reason, object(details.get(0)).get("reason"));
        assertNull(control.idem(recordId(owner, key)));
        assertEquals(0, count("assets", owner));
    }

    static Stream<Arguments> invalidInputs() {
        var missing = body("WORK_MEDIA", "image/png", 4);
        missing.remove("fileName");
        return Stream.of(
                Arguments.of(missing, "fileName", "REQUIRED"),
                Arguments.of(with("fileName", ""), "fileName", "REQUIRED"),
                Arguments.of(with("fileName", "x".repeat(256)), "fileName", "TOO_LONG"),
                Arguments.of(with("purpose", "UNKNOWN"), "purpose", "NOT_ALLOWED"),
                Arguments.of(with("sizeBytes", null), "sizeBytes", "REQUIRED"),
                Arguments.of(with("sizeBytes", 0), "sizeBytes", "OUT_OF_RANGE"),
                Arguments.of(with("sizeBytes", -1), "sizeBytes", "OUT_OF_RANGE"),
                Arguments.of(with("sizeBytes", 1.5), "sizeBytes", "INVALID_FORMAT"),
                Arguments.of(with("sizeBytes", 9_007_199_254_740_992L), "sizeBytes", "OUT_OF_RANGE"),
                Arguments.of(with("ownerId", "forged"), "ownerId", "NOT_ALLOWED"));
    }

    @Test
    void ignoresFileName() throws Exception {
        Account owner = account();
        Map<String, Object> input = with("fileName", "../../outside.exe");
        Ticket upload = ticket(owner, create(owner, Values.id(), input));
        transferred(transfer(upload, PAYLOAD));
        assertEquals(List.of("originals/" + upload.ticket().assetId()), files());
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "invalid", "revoked"})
    void needsLogin(String mode) throws Exception {
        Account owner = account();
        Ticket upload = upload(owner);
        String token = "guest".equals(mode) ? null : "invalid".equals(mode) ? "invalid" : owner.access();
        if ("revoked".equals(mode)) jdbc.update("UPDATE sessions SET revoked=TRUE WHERE id=?", owner.session());
        var create = post(PATH).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", Values.id())
                .content(json.write(body("WORK_MEDIA", "image/png", 4)));
        var cancel = delete(PATH + "/" + upload.ticket().uploadId());
        if (token != null) {
            create.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            cancel.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        String code = "guest".equals(mode) ? "AUTH_REQUIRED" : "invalid".equals(mode) ? "TOKEN_INVALID" : "SESSION_REVOKED";
        assertError(perform(create), 401, code);
        assertError(perform(cancel), 401, code);
        assertEquals(1, count("uploads", owner));
        assertEquals("CREATED", row(upload).getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "not-a-uuid", "1-1-1-1-1"})
    void invalidKey(String key) throws Exception {
        Account owner = account();
        assertError(create(owner, "missing".equals(key) ? null : key, body("WORK_MEDIA", "image/png", 4)),
                400, "BAD_REQUEST");
        assertEquals(0, count("uploads", owner));
        assertEquals(0, count("assets", owner));
    }

    @Test
    void sameKey() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body("WORK_MEDIA", "image/png", 4);
        Map<String, Object> first = data(create(owner, key, input), 201);
        assertEquals(first, data(create(owner, key, input), 201));
        assertEquals(1, count("uploads", owner));
        assertEquals(1, count("assets", owner));
        String encrypted = control.idem(recordId(owner, key)).getResponseJson();
        assertFalse(encrypted.contains((String) first.get("uploadUrl")));
        input.put("fileName", "changed.png");
        assertError(create(owner, key, input), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void keyScopes() throws Exception {
        Account a = account();
        Account b = account();
        String key = Values.id();
        UploadTicket first = ticket(a, create(a, key, body("WORK_MEDIA", "image/png", 4))).ticket();
        UploadTicket second = ticket(b, create(b, key, body("WORK_MEDIA", "image/png", 4))).ticket();
        UploadTicket third = ticket(a, create(a, Values.id(), body("WORK_MEDIA", "image/png", 4))).ticket();
        assertEquals(3, Set.of(first.assetId(), second.assetId(), third.assetId()).size());
        assertEquals(2, count("uploads", a));
        assertEquals(1, count("uploads", b));
    }

    @Test
    void expiredReplay() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body("WORK_MEDIA", "image/png", 4);
        Map<String, Object> first = data(create(owner, key, input), 201);
        jdbc.update("UPDATE uploads SET expires_at=? WHERE id=?", Values.now() - 1000, first.get("uploadId"));
        assertEquals(first, data(create(owner, key, input), 201));
        Ticket replay = new Ticket(owner, json.convert(first, UploadTicket.class));
        assertError(transfer(replay, PAYLOAD), 410, "UPLOAD_EXPIRED");
        assertEquals(1, count("uploads", owner));
    }

    @Test
    void createRollback() throws Exception {
        Account owner = account();
        String key = Values.id();
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        doAnswer(invocation -> {
            actual.insertUpload(invocation.getArgument(0));
            throw new IllegalStateException("Failure after real upload insertion");
        }).when(assets).insertUpload(any(UploadRow.class));
        assertError(create(owner, key, body("WORK_MEDIA", "image/png", 4)), 500, "INTERNAL_ERROR");
        assertEquals(0, count("assets", owner));
        assertEquals(0, count("uploads", owner));
        assertNull(control.idem(recordId(owner, key)));
        doAnswer(invocation -> {
            actual.insertUpload(invocation.getArgument(0));
            return null;
        }).when(assets).insertUpload(any(UploadRow.class));
        ticket(owner, create(owner, key, body("WORK_MEDIA", "image/png", 4)));
    }

    @Test
    void capabilities() throws Exception {
        Map<String, Object> result = data(perform(get("/api/v1/system/capabilities")), 200);
        assertEquals(5, ((List<?>) result.get("uploadRules")).size());
        assertEquals(json.read(json.write(service.rules()), List.class), result.get("uploadRules"));
        assertEquals(9L, ((Number) result.get("maxImagesPerWork")).longValue());
        assertEquals(120L, ((Number) result.get("maxTitleLength")).longValue());
        assertEquals(2000L, ((Number) result.get("maxCommentLength")).longValue());
        assertEquals(List.of(), result.get("musicEmbedProviders"));
    }

    @Test
    void transfers() throws Exception {
        Ticket upload = upload(account());
        UploadRow before = row(upload);
        transferred(transfer(upload, PAYLOAD));
        assertEquals("UPLOADED", row(upload).getState());
        assertNull(row(upload).getFinishedAt());
        assertEquals(before.getExpiresAt(), row(upload).getExpiresAt());
        assertEquals("UPLOADED", asset(upload).getStatus());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(storage.path(asset(upload).getStorageKey())));
        assertEquals(List.of(asset(upload).getStorageKey()), files());
    }

    @Test
    void bypassesJsonLimit() throws Exception {
        byte[] bytes = new byte[1_048_577];
        Arrays.fill(bytes, (byte) 17);
        Ticket upload = upload(account(), "WORK_MEDIA", "image/png", bytes.length);
        transferred(transfer(upload, bytes));
        assertArrayEquals(bytes, Files.readAllBytes(storage.path(asset(upload).getStorageKey())));
        assertEquals("UPLOADED", row(upload).getState());
    }

    @Test
    void jsonLimit() throws Exception {
        Account owner = account();
        assertError(create(owner, Values.id(), with("fileName", "x".repeat(1_048_577))),
                413, "FILE_TOO_LARGE");
        assertEquals(0, count("assets", owner));
        assertEquals(0, count("uploads", owner));
    }

    @Test
    void ticketWithoutSession() throws Exception {
        Account owner = account();
        Ticket upload = upload(owner);
        jdbc.update("UPDATE sessions SET revoked=TRUE WHERE id=?", owner.session());
        transferred(transfer(upload, PAYLOAD));
        assertEquals("UPLOADED", row(upload).getState());
    }

    @Test
    void missingTicket() throws Exception {
        assertError(perform(put(BINARY).content(PAYLOAD)), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "malformed", "tampered", "expired", "purpose", "missing-id", "number-id", "empty-id", "long-id"})
    void invalidTicket(String kind) throws Exception {
        Ticket upload = upload(account());
        String token = switch (kind) {
            case "empty" -> "";
            case "malformed" -> "not-a-ticket";
            case "expired" -> crypto.ticket("upload", Values.map("upload", upload.ticket().uploadId()), -1000);
            case "purpose" -> crypto.ticket("read", Values.map("upload", upload.ticket().uploadId()), 60_000);
            case "missing-id" -> crypto.ticket("upload", Map.of(), 60_000);
            case "number-id" -> crypto.ticket("upload", Values.map("upload", 123), 60_000);
            case "empty-id" -> crypto.ticket("upload", Values.map("upload", ""), 60_000);
            case "long-id" -> crypto.ticket("upload", Values.map("upload", "x".repeat(65)), 60_000);
            default -> {
                String[] parts = upload.token().split("\\.");
                yield parts[0] + "." + (parts[1].startsWith("A") ? "B" : "A") + parts[1].substring(1);
            }
        };
        assertError(transfer(token, PAYLOAD), 410, "UPLOAD_EXPIRED");
        assertEquals("CREATED", row(upload).getState());
        assertEquals(List.of(), files());
    }

    @Test
    void missingUpload() throws Exception {
        String token = crypto.ticket("upload", Values.map("upload", Values.id()), 60_000);
        assertError(transfer(token, PAYLOAD), 404, "UPLOAD_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVING", "UPLOADED", "COMPLETED", "CANCELLED"})
    void rejectsReplay(String state) throws Exception {
        Ticket upload = upload(account());
        jdbc.update("UPDATE uploads SET state=? WHERE id=?", state, upload.ticket().uploadId());
        assertError(transfer(upload, PAYLOAD), 409, "UPLOAD_ALREADY_COMPLETED");
        assertEquals(state, row(upload).getState());
        assertEquals(List.of(), files());
    }

    @Test
    void databaseExpiry() throws Exception {
        Ticket upload = upload(account());
        jdbc.update("UPDATE uploads SET expires_at=? WHERE id=?", Values.now() - 1000, upload.ticket().uploadId());
        assertError(transfer(upload, PAYLOAD), 410, "UPLOAD_EXPIRED");
        assertEquals(List.of(), files());
    }

    @Test
    void shortBody() throws Exception {
        Ticket upload = upload(account());
        MockHttpServletResponse response = transfer(upload, new byte[] {1, 2});
        assertError(response, 422, "VALIDATION_FAILED");
        assertEquals("sizeBytes", object(((List<?>) json.readMap(response.getContentAsString()).get("details")).get(0)).get("field"));
        assertReset(upload);
        transferred(transfer(upload, PAYLOAD));
    }

    @Test
    void oversizedBody() throws Exception {
        Ticket upload = upload(account());
        assertError(transfer(upload, new byte[5]), 413, "FILE_TOO_LARGE");
        assertReset(upload);
        transferred(transfer(upload, PAYLOAD));
    }

    @Test
    void mediaMaximum() throws Exception {
        Ticket upload = upload(account(), "AVATAR", "image/png", 4);
        int bytes = 5 * 1024 * 1024 + 1;
        jdbc.update("UPDATE uploads SET expected_size=? WHERE id=?", bytes, upload.ticket().uploadId());
        assertError(transfer(upload, new byte[bytes]), 413, "FILE_TOO_LARGE");
        assertReset(upload);
    }

    @Test
    void streamFailure() throws Exception {
        Ticket upload = upload(account());
        IOException error = assertThrows(IOException.class,
                () -> service.transfer(upload.token(), brokenInput()));
        assertEquals("read failed", error.getMessage());
        assertReset(upload);
        transferred(transfer(upload, PAYLOAD));
    }

    @Test
    void finalWriteRollback() throws Exception {
        Ticket upload = upload(account());
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        doAnswer(invocation -> {
            UploadRow row = invocation.getArgument(0);
            actual.updateUpload(row);
            if ("UPLOADED".equals(row.getState())) throw new IllegalStateException("Failure after upload state update");
            return null;
        }).when(assets).updateUpload(any(UploadRow.class));
        assertError(transfer(upload, PAYLOAD), 500, "INTERNAL_ERROR");
        assertReset(upload);
    }

    @Test
    void assetWriteRollback() throws Exception {
        Ticket upload = upload(account());
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        doAnswer(invocation -> {
            AssetRow row = invocation.getArgument(0);
            actual.updateAsset(row);
            if ("UPLOADED".equals(row.getStatus())) {
                throw new IllegalStateException("Failure after uploaded asset update");
            }
            return null;
        }).when(assets).updateAsset(any(AssetRow.class));
        assertError(transfer(upload, PAYLOAD), 500, "INTERNAL_ERROR");
        assertReset(upload);
    }

    @Test
    void cleanupSuppressed() throws Exception {
        Ticket upload = upload(account());
        String key = asset(upload).getStorageKey();
        doThrow(new IOException("delete failed")).when(storage).delete(key);
        ApiException error = assertThrows(ApiException.class,
                () -> service.transfer(upload.token(), new ByteArrayInputStream(new byte[] {1, 2})));
        assertEquals(422, error.status);
        assertEquals("delete failed", error.getSuppressed()[0].getMessage());
        assertEquals("CREATED", row(upload).getState());
        assertTrue(Files.exists(storage.path(asset(upload).getStorageKey())));
    }

    @Test
    void recoverySuppressed() throws Exception {
        Ticket upload = upload(account());
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        doAnswer(invocation -> {
            UploadRow row = invocation.getArgument(0);
            if ("CREATED".equals(row.getState())) throw new DataAccessResourceFailureException("reset failed");
            actual.updateUpload(row);
            return null;
        }).when(assets).updateUpload(any(UploadRow.class));
        IOException error = assertThrows(IOException.class, () -> service.transfer(upload.token(), brokenInput()));
        assertEquals("read failed", error.getMessage());
        assertInstanceOf(DataAccessResourceFailureException.class, error.getSuppressed()[0]);
        assertEquals("RECEIVING", row(upload).getState());
        assertEquals(List.of(), files());
    }

    @Test
    void rechecksHint() throws Exception {
        Ticket upload = upload(account());
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        var reads = new AtomicInteger();
        doAnswer(invocation -> {
            UploadRow found = actual.upload(upload.ticket().uploadId());
            if (reads.incrementAndGet() == 1) jdbc.update("DELETE FROM uploads WHERE id=?", found.getId());
            return found;
        }).when(assets).upload(upload.ticket().uploadId());
        assertError(transfer(upload, PAYLOAD), 404, "UPLOAD_NOT_FOUND");
        assertEquals(2, reads.get());
        assertEquals(List.of(), files());
    }

    @Test
    void concurrentTransfer() throws Exception {
        Ticket upload = upload(account());
        try (BlockedWrite blocked = blockWrite(upload)) {
            var first = blocked.start(() -> transfer(upload, PAYLOAD));
            blocked.await();
            assertEquals("RECEIVING", row(upload).getState());
            assertError(transfer(upload, PAYLOAD), 409, "UPLOAD_ALREADY_COMPLETED");
            blocked.release();
            transferred(first.get(10, TimeUnit.SECONDS));
        }
        assertArrayEquals(PAYLOAD, Files.readAllBytes(storage.path(asset(upload).getStorageKey())));
    }

    @Test
    void cancelDuringTransfer() throws Exception {
        Ticket upload = upload(account());
        try (BlockedWrite blocked = blockWrite(upload)) {
            var first = blocked.start(() -> transfer(upload, PAYLOAD));
            blocked.await();
            ack(cancel(upload, upload.owner()));
            long finished = row(upload).getFinishedAt();
            blocked.release();
            assertError(first.get(10, TimeUnit.SECONDS), 410, "UPLOAD_EXPIRED");
            assertEquals("CANCELLED", row(upload).getState());
            assertEquals(finished, row(upload).getFinishedAt());
        }
        assertEquals("FAILED", asset(upload).getStatus());
        assertEquals("UPLOAD_CANCELLED", asset(upload).getFailureCode());
        assertEquals(List.of(), files());
    }

    @Test
    void expiresDuringTransfer() throws Exception {
        Ticket upload = upload(account());
        try (BlockedWrite blocked = blockWrite(upload)) {
            var first = blocked.start(() -> transfer(upload, PAYLOAD));
            blocked.await();
            jdbc.update("UPDATE uploads SET expires_at=? WHERE id=?", Values.now() - 1000, upload.ticket().uploadId());
            blocked.release();
            assertError(first.get(10, TimeUnit.SECONDS), 410, "UPLOAD_EXPIRED");
        }
        assertEquals(List.of(), files());
        assertNotEquals("UPLOADED", row(upload).getState());
        assertError(transfer(upload, PAYLOAD), 410, "UPLOAD_EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "RECEIVING", "UPLOADED"})
    void cancels(String state) throws Exception {
        Ticket upload = upload(account());
        jdbc.update("UPDATE uploads SET state=? WHERE id=?", state, upload.ticket().uploadId());
        long before = Values.now();
        ack(cancel(upload, upload.owner()));
        assertEquals("CANCELLED", row(upload).getState());
        assertTrue(row(upload).getFinishedAt() >= before && row(upload).getFinishedAt() <= Values.now());
        assertEquals("FAILED", asset(upload).getStatus());
        assertEquals("UPLOAD_CANCELLED", asset(upload).getFailureCode());
        assertError(transfer(upload, PAYLOAD), 409, "UPLOAD_ALREADY_COMPLETED");
    }

    @Test
    void repeatCancel() throws Exception {
        Ticket upload = upload(account());
        ack(cancel(upload, upload.owner()));
        long finished = Values.now() - 60_000;
        jdbc.update("UPDATE uploads SET finished_at=? WHERE id=?", finished, upload.ticket().uploadId());
        AssetRow before = asset(upload);
        ack(cancel(upload, upload.owner()));
        assertEquals(finished, row(upload).getFinishedAt());
        assertEquals(before, asset(upload));
    }

    @Test
    void expiredTerminal() throws Exception {
        Ticket upload = upload(account());
        long finished = Values.now() - 60_000;
        jdbc.update("UPDATE uploads SET state='EXPIRED',finished_at=? WHERE id=?", finished, upload.ticket().uploadId());
        jdbc.update("UPDATE assets SET status='FAILED',failure_code='UPLOAD_EXPIRED' WHERE id=?", upload.ticket().assetId());
        AssetRow before = asset(upload);
        ack(cancel(upload, upload.owner()));
        assertEquals("EXPIRED", row(upload).getState());
        assertEquals(finished, row(upload).getFinishedAt());
        assertEquals(before, asset(upload));
    }

    @Test
    void completedCancel() throws Exception {
        Ticket upload = upload(account());
        jdbc.update("UPDATE uploads SET state='COMPLETED',finished_at=? WHERE id=?", Values.now(), upload.ticket().uploadId());
        UploadRow before = row(upload);
        AssetRow asset = asset(upload);
        assertError(cancel(upload, upload.owner()), 409, "UPLOAD_ALREADY_COMPLETED");
        assertEquals(before, row(upload));
        assertEquals(asset, asset(upload));
    }

    @Test
    void cancelOwnership() throws Exception {
        Ticket upload = upload(account());
        assertError(cancel(upload, account()), 404, "UPLOAD_NOT_FOUND");
        assertError(perform(delete(PATH + "/" + Values.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + upload.owner().access())), 404, "UPLOAD_NOT_FOUND");
        assertEquals("CREATED", row(upload).getState());
    }

    @Test
    void cancelRollback() throws Exception {
        Ticket upload = upload(account());
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        doAnswer(invocation -> {
            actual.updateAsset(invocation.getArgument(0));
            throw new IllegalStateException("Failure after real asset cancellation");
        }).when(assets).updateAsset(any(AssetRow.class));
        assertError(cancel(upload, upload.owner()), 500, "INTERNAL_ERROR");
        assertEquals("CREATED", row(upload).getState());
        assertNull(row(upload).getFinishedAt());
        assertEquals("CREATED", asset(upload).getStatus());
        assertNull(asset(upload).getFailureCode());
    }

    @Test
    void cancelRechecks() throws Exception {
        Ticket upload = upload(account());
        AssetMapper actual = sql.getMapper(AssetMapper.class);
        var reads = new AtomicInteger();
        doAnswer(invocation -> {
            UploadRow found = actual.upload(upload.ticket().uploadId());
            if (reads.incrementAndGet() == 1) jdbc.update(
                    "UPDATE uploads SET state='COMPLETED',finished_at=? WHERE id=?", Values.now(), found.getId());
            return found;
        }).when(assets).upload(upload.ticket().uploadId());
        assertError(cancel(upload, upload.owner()), 409, "UPLOAD_ALREADY_COMPLETED");
        assertEquals(2, reads.get());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT state FROM uploads WHERE id=?", String.class, upload.ticket().uploadId()));
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id,email,username,password_hash,display_name,bio,
                  email_verified,theme,motion,auto_play_media,created_at)
                VALUES (?,?,?,'private-password-hash','上传用户','',TRUE,'SYSTEM','SYSTEM',TRUE,?)
                """, user, user + "@example.test", "upload_" + user.replace("-", "").substring(0, 12), Values.now());
        userIds.add(user);
        SessionRow row = new SessionRow();
        row.setId(Values.id());
        row.setUserId(user);
        row.setRefreshHash(crypto.digest(crypto.randomToken()));
        row.setCreatedAt(Values.now());
        row.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(row);
        return new Account(user, row.getId(), crypto.jwt(user, row.getId(), 900));
    }

    private Ticket upload(Account owner) throws Exception {
        return upload(owner, "WORK_MEDIA", "image/png", PAYLOAD.length);
    }

    private Ticket upload(Account owner, String purpose, String mime, long size) throws Exception {
        return ticket(owner, create(owner, Values.id(), body(purpose, mime, size)));
    }

    private Ticket ticket(Account owner, MockHttpServletResponse response) throws Exception {
        Map<String, Object> value = data(response, 201);
        assertEquals(Set.of("uploadId", "assetId", "uploadUrl", "method", "headers", "expiresAt", "maxBytes"), value.keySet());
        return new Ticket(owner, json.convert(value, UploadTicket.class));
    }

    private static Map<String, Object> body(String purpose, String mime, long size) {
        return Values.map("purpose", purpose, "fileName", "photo.png", "contentType", mime, "sizeBytes", size);
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> body = body("WORK_MEDIA", "image/png", 4);
        body.put(field, value);
        return body;
    }

    private String recordId(Account owner, String key) {
        String id = crypto.digest(owner.user() + "\nPOST\n/uploads\n" + key);
        idemIds.add(id);
        return id;
    }

    private MockHttpServletResponse create(Account owner, String key, Map<String, Object> input) throws Exception {
        if (key != null) recordId(owner, key);
        var request = post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(input));
        if (key != null) request.header("Idempotency-Key", key);
        return perform(request);
    }

    private MockHttpServletResponse transfer(Ticket upload, byte[] bytes) throws Exception {
        return transfer(upload.token(), bytes);
    }

    private MockHttpServletResponse transfer(String token, byte[] bytes) throws Exception {
        return perform(put(BINARY).param("ticket", token).contentType(MediaType.APPLICATION_OCTET_STREAM).content(bytes));
    }

    private MockHttpServletResponse cancel(Ticket upload, Account actor) throws Exception {
        return perform(delete(PATH + "/" + upload.ticket().uploadId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.access()));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "upload-" + Values.id())).andReturn().getResponse();
    }

    private AssetRow asset(Ticket upload) {
        return assets.asset(upload.ticket().assetId());
    }

    private UploadRow row(Ticket upload) {
        return assets.upload(upload.ticket().uploadId());
    }

    private int count(String table, Account owner) {
        assertTrue(Set.of("assets", "uploads").contains(table));
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE owner_id=?", Integer.class, owner.user());
    }

    private List<String> files() throws IOException {
        try (var walk = Files.walk(MEDIA_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> MEDIA_ROOT.relativize(path).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private void assertReset(Ticket upload) throws IOException {
        assertEquals("CREATED", row(upload).getState());
        assertNull(row(upload).getFinishedAt());
        assertEquals("CREATED", asset(upload).getStatus());
        assertEquals(List.of(), files());
    }

    private void transferred(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("", response.getContentAsString());
        assertNull(response.getContentType());
        assertNotNull(response.getHeader("X-Request-Id"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private void ack(MockHttpServletResponse response) throws Exception {
        assertEquals(Map.of("success", true), data(response, 200));
    }

    private Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        return object(body.get("data"));
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertFalse(body.containsKey("data"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private InputStream brokenInput() {
        return new InputStream() {
            private boolean sent;

            @Override
            public int read() throws IOException {
                throw new IOException("read failed");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (sent) throw new IOException("read failed");
                sent = true;
                bytes[offset] = 1;
                return 1;
            }
        };
    }

    private BlockedWrite blockWrite(Ticket upload) throws IOException {
        BlockedWrite blocked = new BlockedWrite();
        String key = asset(upload).getStorageKey();
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "网络传输不能持有数据库事务");
            assertEquals("RECEIVING", row(upload).getState());
            blocked.entered.countDown();
            assertTrue(blocked.proceed.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(storage).write(eq(key), any(InputStream.class), anyLong());
        return blocked;
    }

    private static class BlockedWrite implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final java.util.concurrent.ExecutorService pool = Executors.newSingleThreadExecutor();

        java.util.concurrent.Future<MockHttpServletResponse> start(Callable<MockHttpServletResponse> action) {
            return pool.submit(action);
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

    private record Account(String user, String session, String access) {}
    private record Ticket(Account owner, UploadTicket ticket) {
        String token() {
            return URLDecoder.decode(
                    URI.create(ticket.uploadUrl()).getRawQuery().substring("ticket=".length()), StandardCharsets.UTF_8);
        }
    }
}
