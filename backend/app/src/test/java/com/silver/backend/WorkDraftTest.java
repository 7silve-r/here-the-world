package com.silver.backend;

import com.silver.backend.business.dto.WorkDetail;
import com.silver.backend.business.dto.WorkInput;
import com.silver.backend.business.persistence.IdempotencyRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.ControlMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class WorkDraftTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/works";

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private ControlMapper control;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private WorkService service;

    private final List<String> userIds = new ArrayList<>();
    private final Set<String> idemIds = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanFixtures() {
        for (String id : idemIds) jdbc.update("DELETE FROM idempotency WHERE id = ?", id);
        for (String user : userIds) {
            for (String work : jdbc.queryForList("SELECT id FROM works WHERE owner_id = ?", String.class, user)) {
                jdbc.update("DELETE FROM work_assets WHERE work_id = ?", work);
                jdbc.update("DELETE FROM works WHERE id = ?", work);
            }
            jdbc.update("DELETE FROM assets WHERE owner_id = ?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void creates() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        input.put("title", "  草稿标题  ");
        input.put("body", "  草稿正文\n ");
        long before = Values.now();

        MockHttpServletResponse response = create(owner, key, input);

        Map<String, Object> result = created(response);
        WorkRow row = works.get((String) result.get("id"));
        assertEquals(owner.user(), row.getOwnerId());
        assertEquals("TEXT", row.getKind());
        assertEquals("DRAFT", row.getStatus());
        assertEquals(1L, row.getVersion());
        assertEquals("草稿标题", row.getTitle());
        assertEquals("草稿正文", row.getBody());
        assertTrue(row.getCreatedAt() >= before && row.getCreatedAt() <= Values.now());
        assertEquals(row.getCreatedAt(), row.getUpdatedAt());
        assertNull(row.getPublishedAt());
        assertNull(row.getFailureCode());
        assertNull(row.getPublishTaskId());
        assertNull(row.getCoverAssetId());
        assertNull(row.getMusicLinkId());
        assertEquals(List.of(), works.assets(row.getId()));
        assertEquals(Instant.ofEpochMilli(row.getCreatedAt()).toString(), result.get("createdAt"));
        assertEquals(result.get("createdAt"), result.get("updatedAt"));
        assertEquals(owner.user(), object(result.get("author")).get("id"));
        assertEquals(Map.of("liked", false, "bookmarked", false, "isOwner", true), result.get("viewer"));
        assertEquals(Map.of("likeCount", 0, "bookmarkCount", 0, "commentCount", 0), result.get("stats"));
        assertEquals(owner.session(), users.session(owner.session().getId()));
        assertEquals(result, data(read(row.getId(), owner.access()), 200));
        assertError(read(row.getId(), null), 404, "WORK_NOT_FOUND");
        assertError(read(row.getId(), account().access()), 404, "WORK_NOT_FOUND");

        IdempotencyRow record = control.idem(recordId(owner, key));
        assertNotNull(record);
        assertEquals(crypto.digest(json.write(json.canonical(input))), record.getBodyHash());
        assertNotNull(record.getResponseJson());
        assertFalse(record.getResponseJson().contains(row.getId()));
        Map<String, Object> cached = json.readMap(crypto.decrypt(record.getResponseJson()));
        assertEquals(result, cached.get("data"));
        assertEquals(201, cached.get("status"));
        assertEquals("\"v1\"", cached.get("etag"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t "})
    void blankBody(String text) throws Exception {
        Account owner = account();
        Map<String, Object> input = body();
        input.put("body", text);

        Map<String, Object> result = created(create(owner, Values.id(), input));

        assertEquals("", result.get("body"));
        assertEquals("", result.get("excerpt"));
        assertEquals("", works.get((String) result.get("id")).getBody());
    }

    @Test
    void unicodeBounds() throws Exception {
        Account owner = account();
        Map<String, Object> input = body();
        input.put("title", "🌍".repeat(120));
        input.put("body", "🌍".repeat(20_000));

        Map<String, Object> result = created(create(owner, Values.id(), input));

        assertEquals(input.get("title"), result.get("title"));
        assertEquals(input.get("body"), result.get("body"));
        assertEquals("🌍".repeat(280), result.get("excerpt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missingKind", "missingTitle", "missingBody", "missingAssets", "missingCover", "missingMusic",
            "unknown", "badKind", "titleNull", "bodyType", "assetsType", "longTitle", "longBody"
    })
    void badInput(String kind) throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        switch (kind) {
            case "missingKind" -> input.remove("kind");
            case "missingTitle" -> input.remove("title");
            case "missingBody" -> input.remove("body");
            case "missingAssets" -> input.remove("mediaAssetIds");
            case "missingCover" -> input.remove("coverAssetId");
            case "missingMusic" -> input.remove("musicLinkId");
            case "unknown" -> input.put("ownerId", Values.id());
            case "badKind" -> input.put("kind", "UNKNOWN");
            case "titleNull" -> input.put("title", null);
            case "bodyType" -> input.put("body", 123);
            case "assetsType" -> input.put("mediaAssetIds", "not-an-array");
            case "longTitle" -> input.put("title", "x".repeat(121));
            case "longBody" -> input.put("body", "x".repeat(20_001));
            default -> throw new IllegalArgumentException(kind);
        }

        assertError(create(owner, key, input), 422, "VALIDATION_FAILED");

        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)));
    }

    @Test
    void retriesFailure() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        input.put("title", " \t\n ");

        assertError(create(owner, key, input), 422, "VALIDATION_FAILED");

        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)), "失败不能占住幂等键");
        input.put("title", "修正后的标题");
        assertEquals("修正后的标题", created(create(owner, key, input)).get("title"));
        assertEquals(1, workCount(owner));
    }

    @Test
    void rejectsMedia() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        input.put("mediaAssetIds", List.of(asset(owner, "WORK_MEDIA", "IMAGE", "READY")));

        assertError(create(owner, key, input), 422, "VALIDATION_FAILED");

        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)));
    }

    @Test
    void rejectsMusic() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        input.put("musicLinkId", Values.id());

        assertError(create(owner, key, input), 422, "VALIDATION_FAILED");

        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "foreign", "purpose", "type", "cleaned", "missingFile"})
    void badCover(String kind) throws Exception {
        Account owner = account();
        String key = Values.id();
        String cover;
        String code;
        switch (kind) {
            case "missing" -> {
                cover = Values.id();
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "foreign" -> {
                cover = asset(account(), "WORK_COVER", "IMAGE", "READY");
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "purpose" -> {
                cover = asset(owner, "AVATAR", "IMAGE", "READY");
                code = "ASSET_TYPE_MISMATCH";
            }
            case "type" -> {
                cover = asset(owner, "WORK_COVER", "VIDEO", "READY");
                code = "ASSET_TYPE_MISMATCH";
            }
            default -> {
                cover = asset(owner, "WORK_COVER", "IMAGE", "READY");
                if ("cleaned".equals(kind)) {
                    jdbc.update("UPDATE assets SET cleaned_at = ? WHERE id = ?", Values.now(), cover);
                } else {
                    jdbc.update("UPDATE assets SET storage_key = NULL WHERE id = ?", cover);
                }
                code = "ASSET_NOT_READY";
            }
        }
        Map<String, Object> input = body();
        input.put("coverAssetId", cover);

        assertError(create(owner, key, input), 422, code);

        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "PROCESSING"})
    void coverDraft(String status) throws Exception {
        Account owner = account();
        String cover = asset(owner, "WORK_COVER", "IMAGE", status);
        Map<String, Object> input = body();
        input.put("coverAssetId", cover);

        Map<String, Object> result = created(create(owner, Values.id(), input));

        WorkRow row = works.get((String) result.get("id"));
        assertEquals(cover, row.getCoverAssetId());
        assertEquals(List.of(), works.assets(row.getId()));
        assertEquals(cover, object(result.get("coverAsset")).get("id"));
        assertEquals(status, object(result.get("coverAsset")).get("status"));
        if ("READY".equals(status)) {
            assertTrue(((String) object(result.get("cover")).get("url")).contains("/media/private/"));
        } else {
            assertNull(result.get("cover"));
            assertNull(object(result.get("coverAsset")).get("url"));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1"})
    void badKey(String key) throws Exception {
        Account owner = account();

        assertError(create(owner, key, body()), 400, "BAD_REQUEST");

        assertEquals(0, workCount(owner));
    }

    @Test
    void needsLogin() throws Exception {
        Account owner = account();

        assertError(perform(post(PATH).header("Idempotency-Key", Values.id())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body()))), 401, "AUTH_REQUIRED");

        assertEquals(0, workCount(owner));
    }

    @Test
    void revokedSession() throws Exception {
        Account owner = account();
        String key = Values.id();
        jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", owner.session().getId());

        assertError(create(owner, key, body()), 401, "SESSION_REVOKED");

        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)));
    }

    @Test
    void sameKey() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        Map<String, Object> first = created(create(owner, key, input));
        var reversed = new LinkedHashMap<String, Object>();
        var fields = new ArrayList<>(input.keySet());
        Collections.reverse(fields);
        for (String field : fields) reversed.put(field, input.get(field));

        Map<String, Object> replay = created(create(owner, key, reversed));

        assertEquals(first, replay);
        assertEquals(1, workCount(owner));
    }

    @Test
    void conflict() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> input = body();
        Map<String, Object> first = created(create(owner, key, input));
        input.put("title", "另一个标题");

        assertError(create(owner, key, input), 409, "IDEMPOTENCY_CONFLICT");

        assertEquals(1, workCount(owner));
        assertEquals(first.get("title"), works.get((String) first.get("id")).getTitle());
    }

    @Test
    void newKey() throws Exception {
        Account owner = account();

        Map<String, Object> first = created(create(owner, Values.id(), body()));
        Map<String, Object> second = created(create(owner, Values.id(), body()));

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(2, workCount(owner));
    }

    @Test
    void userScope() throws Exception {
        Account first = account();
        Account second = account();
        String key = Values.id();

        Map<String, Object> a = created(create(first, key, body()));
        Map<String, Object> b = created(create(second, key, body()));

        assertNotEquals(a.get("id"), b.get("id"));
        assertEquals(first.user(), object(a.get("author")).get("id"));
        assertEquals(second.user(), object(b.get("author")).get("id"));
        assertEquals(1, workCount(first));
        assertEquals(1, workCount(second));
    }

    @Test
    void cachedOriginal() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> original = created(create(owner, key, body()));
        String work = (String) original.get("id");
        jdbc.update("UPDATE works SET title = '后来修改的标题', version = 2 WHERE id = ?", work);

        Map<String, Object> replay = created(create(owner, key, body()));

        assertEquals(original, replay, "重试返回首次创建结果，不重新执行或组装当前版本");
        assertEquals(2L, works.get(work).getVersion());
        assertEquals("后来修改的标题", works.get(work).getTitle());
        assertEquals(1, workCount(owner));
    }

    @Test
    void activeClaim() throws Exception {
        Account owner = account();
        String key = Values.id();
        String id = recordId(owner, key);
        Map<String, Object> input = body();
        jdbc.update("""
                INSERT INTO idempotency (id, body_hash, expires_at, lease_until, lease_token)
                VALUES (?, ?, ?, ?, 'previous-owner')
                """, id, crypto.digest(json.write(json.canonical(input))),
                Values.now() + 86_400_000, Values.now() + 60_000);

        MockHttpServletResponse response = create(owner, key, input);

        assertError(response, 409, "REQUEST_IN_PROGRESS");
        assertEquals("2", response.getHeader("Retry-After"));
        assertEquals(0, workCount(owner));
        jdbc.update("UPDATE idempotency SET lease_until = ? WHERE id = ?", Values.now() - 1000, id);
        created(create(owner, key, input));
        assertEquals(1, workCount(owner));
        assertNotEquals("previous-owner", control.idem(id).getLeaseToken());
    }

    @Test
    void concurrentSame() throws Exception {
        Account owner = account();
        String key = Values.id();

        List<MockHttpServletResponse> responses = together(owner, key, List.of(body(), body()));

        String work = null;
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 201) {
                String id = (String) created(response).get("id");
                if (work != null) assertEquals(work, id);
                work = id;
            } else {
                assertError(response, 409, "REQUEST_IN_PROGRESS");
                assertEquals("2", response.getHeader("Retry-After"));
            }
        }
        assertNotNull(work);
        assertEquals(work, created(create(owner, key, body())).get("id"));
        assertEquals(1, workCount(owner));
    }

    @Test
    void concurrentConflict() throws Exception {
        Account owner = account();
        String key = Values.id();
        Map<String, Object> other = body();
        other.put("title", "第二份内容");

        List<MockHttpServletResponse> responses = together(owner, key, List.of(body(), other));

        assertEquals(1, responses.stream().filter(response -> response.getStatus() == 201).count());
        assertEquals(1, responses.stream().filter(response -> response.getStatus() == 409).count());
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 201) created(response);
            else assertError(response, 409, "IDEMPOTENCY_CONFLICT");
        }
        assertEquals(1, workCount(owner));
    }

    @Test
    void rollsBack() throws Exception {
        Account owner = account();
        String key = Values.id();
        var inserted = new AtomicReference<String>();
        // 真实业务完成插入后注入故障，验证创建与幂等事务没有留下半条数据。
        doAnswer(invocation -> {
            Reply<?> result = (Reply<?>) invocation.callRealMethod();
            inserted.set(((WorkDetail) result.data()).id());
            assertEquals(1, workCount(owner));
            throw new IllegalStateException("Failure after real draft insertion");
        }).when(service).create(any(Viewer.class), any(WorkInput.class));

        assertError(create(owner, key, body()), 500, "INTERNAL_ERROR");

        assertNotNull(inserted.get());
        assertNull(works.get(inserted.get()));
        assertEquals(0, workCount(owner));
        assertNull(control.idem(recordId(owner, key)));
        doCallRealMethod().when(service).create(any(Viewer.class), any(WorkInput.class));
        Map<String, Object> result = created(create(owner, key, body()));
        assertNotEquals(inserted.get(), result.get("id"));
        assertEquals(1, workCount(owner));
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '草稿作者', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "draft_" + user.replace("-", "").substring(0, 12), Values.now());
        userIds.add(user);
        var row = new SessionRow();
        row.setId(Values.id());
        row.setUserId(user);
        row.setRefreshHash(crypto.digest(crypto.randomToken()));
        row.setCreatedAt(Values.now());
        row.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(row);
        return new Account(user, row, crypto.jwt(user, row.getId(), 900));
    }

    private String asset(Account owner, String purpose, String type, String status) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, thumbnail_key, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1024, ?)
                """, id, owner.user(), purpose, type, status, "IMAGE".equals(type) ? "image/jpeg" : "video/mp4",
                "original/" + id, "thumbnails/" + id + ".jpg", Values.now());
        return id;
    }

    private Map<String, Object> body() {
        return Values.map("kind", "TEXT", "title", "草稿标题", "body", "草稿正文",
                "mediaAssetIds", List.of(), "coverAssetId", null, "musicLinkId", null);
    }

    private String recordId(Account owner, String key) {
        String id = crypto.digest(owner.user() + "\nPOST\n/works\n" + key);
        idemIds.add(id);
        return id;
    }

    private int workCount(Account owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM works WHERE owner_id = ?", Integer.class, owner.user());
    }

    private MockHttpServletResponse create(Account owner, String key, Map<String, Object> body) throws Exception {
        if (key != null) recordId(owner, key);
        var request = post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        if (key != null) request.header("Idempotency-Key", key);
        return perform(request);
    }

    private MockHttpServletResponse read(String work, String token) throws Exception {
        var request = get(PATH + "/" + work);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return perform(request);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "work-draft-" + Values.id()))
                .andReturn().getResponse();
    }

    private List<MockHttpServletResponse> together(Account owner, String key, List<Map<String, Object>> inputs)
            throws Exception {
        var pool = Executors.newFixedThreadPool(inputs.size());
        var ready = new CountDownLatch(inputs.size());
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (Map<String, Object> input : inputs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return create(owner, key, input);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<MockHttpServletResponse>();
            for (Future<MockHttpServletResponse> future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Map<String, Object> created(MockHttpServletResponse response) throws Exception {
        Map<String, Object> result = data(response, 201);
        assertEquals("\"v1\"", response.getHeader(HttpHeaders.ETAG));
        assertEquals("DRAFT", result.get("status"));
        assertEquals(1L, ((Number) result.get("version")).longValue());
        assertNull(result.get("publishedAt"));
        assertEquals(List.of(), result.get("assets"));
        return result;
    }

    private Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-draft-"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        return object(body.get("data"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertFalse(body.containsKey("data"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private record Account(String user, SessionRow session, String access) {}
}
