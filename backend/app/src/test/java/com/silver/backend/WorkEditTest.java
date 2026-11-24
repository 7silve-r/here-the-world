package com.silver.backend;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.WorkInput;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.system.Requests;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class WorkEditTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/works/";
    private static final long CREATED = 1_700_000_000_000L;

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private WorkService service;
    @Autowired private Requests requests;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @MockitoSpyBean private Views views;

    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
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
    void saves() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        WorkRow before = works.get(fixture.work().getId());
        String cover = asset(fixture.owner(), "WORK_COVER", "IMAGE");
        Map<String, Object> input = body(fixture);
        input.put("title", "  新标题  ");
        input.put("body", "  新正文\n ");
        input.put("coverAssetId", cover);
        long start = Values.now();

        MockHttpServletResponse response = save(fixture, "\"v7\"", input);

        Map<String, Object> result = saved(response, 8);
        WorkRow after = works.get(before.getId());
        before.setTitle("新标题");
        before.setBody("新正文");
        before.setCoverAssetId(cover);
        before.setVersion(8);
        before.setUpdatedAt(after.getUpdatedAt());
        assertEquals(before, after);
        assertTrue(after.getUpdatedAt() >= start && after.getUpdatedAt() <= Values.now());
        assertEquals(CREATED, after.getCreatedAt());
        assertEquals("新标题", result.get("title"));
        assertEquals("新正文", result.get("body"));
        assertEquals(cover, object(result.get("coverAsset")).get("id"));
        assertTrue(((String) object(result.get("cover")).get("url")).contains("/media/private/"));
        assertEquals(result, data(read(fixture), 200));
        assertEquals(fixture.owner().session(), users.session(fixture.owner().session().getId()));
    }

    @Test
    void failedRecovery() throws Exception {
        Fixture fixture = fixture("FAILED", "TEXT");

        Map<String, Object> result = saved(save(fixture, "\"v7\"", body(fixture)), 8);

        WorkRow after = works.get(fixture.work().getId());
        assertEquals("DRAFT", after.getStatus());
        assertNull(after.getFailureCode());
        assertNull(after.getPublishTaskId());
        assertNull(after.getPublishedAt());
        assertNull(result.get("failureCode"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t "})
    void blankBody(String body) throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        Map<String, Object> input = body(fixture);
        input.put("body", body);

        Map<String, Object> result = saved(save(fixture, "\"v7\"", input), 8);

        assertEquals("", result.get("body"));
        assertEquals("", result.get("excerpt"));
        assertEquals("", works.get(fixture.work().getId()).getBody());
    }

    @Test
    void clearCover() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        Map<String, Object> input = body(fixture);
        input.put("coverAssetId", null);

        Map<String, Object> result = saved(save(fixture, "\"v7\"", input), 8);

        assertNull(result.get("cover"));
        assertNull(result.get("coverAsset"));
        assertNull(works.get(fixture.work().getId()).getCoverAssetId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE id = ?",
                Integer.class, fixture.work().getCoverAssetId()));
    }

    @Test
    void mediaOrder() throws Exception {
        Fixture fixture = fixture("DRAFT", "IMAGE");
        String first = asset(fixture.owner(), "WORK_MEDIA", "IMAGE");
        String second = asset(fixture.owner(), "WORK_MEDIA", "IMAGE");
        works.bind(fixture.work().getId(), first, 0);
        works.bind(fixture.work().getId(), second, 1);
        Map<String, Object> input = body(fixture);
        input.put("mediaAssetIds", List.of(second, first));

        Map<String, Object> result = saved(save(fixture, "\"v7\"", input), 8);

        assertEquals(List.of(second, first), works.assets(fixture.work().getId()));
        List<?> media = (List<?>) result.get("assets");
        assertEquals(List.of(second, first), media.stream().map(item -> object(item).get("id")).toList());
    }

    @Test
    void missingMatch() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");

        assertError(save(fixture, null, body(fixture)), 428, "PRECONDITION_REQUIRED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v7", "\"v0\"", "\"v-1\"", "W/\"v7\"", "*", "\"v07\"",
            "\"v7\", \"v8\"", "\"v9223372036854775808\""})
    void badMatch(String match) throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");

        assertError(save(fixture, match, body(fixture)), 400, "BAD_REQUEST");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"v6\"", "\"v8\""})
    void staleMatch(String match) throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");

        assertError(save(fixture, match, body(fixture)), 412, "VERSION_CONFLICT");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "unknown", "missingBody", "missingAssets", "nullTitle",
            "longTitle", "bodyType", "blankTitle"})
    void badInput(String kind) throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        Map<String, Object> input = body(fixture);
        switch (kind) {
            case "empty" -> input.clear();
            case "unknown" -> input.put("ownerId", Values.id());
            case "missingBody" -> input.remove("body");
            case "missingAssets" -> input.remove("mediaAssetIds");
            case "nullTitle" -> input.put("title", null);
            case "longTitle" -> input.put("title", "x".repeat(121));
            case "bodyType" -> input.put("body", 123);
            case "blankTitle" -> input.put("title", " \n\t ");
            default -> throw new IllegalArgumentException(kind);
        }

        assertError(save(fixture, "\"v7\"", input), 422, "VALIDATION_FAILED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @Test
    void immutableKind() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        Map<String, Object> input = body(fixture);
        input.put("kind", "IMAGE");

        assertError(save(fixture, "\"v7\"", input), 409, "WORK_KIND_IMMUTABLE");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "PROCESSING"})
    void badState(String status) throws Exception {
        Fixture fixture = fixture(status, "TEXT");

        assertError(save(fixture, "\"v7\"", body(fixture)), 409, "WORK_NOT_EDITABLE");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @Test
    void foreignWork() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");

        assertError(perform(request(account().access(), fixture.work().getId(), null, body(fixture))),
                404, "WORK_NOT_FOUND");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @Test
    void missingWork() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");

        assertError(perform(request(fixture.owner().access(), Values.id(), "\"v7\"", body(fixture))),
                404, "WORK_NOT_FOUND");
    }

    @Test
    void needsLogin() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");

        assertError(perform(request(null, fixture.work().getId(), "\"v7\"", body(fixture))), 401, "AUTH_REQUIRED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "revoked"})
    void badBearer(String kind) throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        String token = fixture.owner().access();
        String code;
        if ("invalid".equals(kind)) {
            token = "invalid-token";
            code = "TOKEN_INVALID";
        } else {
            jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", fixture.owner().session().getId());
            code = "SESSION_REVOKED";
        }

        assertError(perform(request(token, fixture.work().getId(), "\"v7\"", body(fixture))), 401, code);

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "foreign", "purpose", "type", "cleaned"})
    void badCover(String kind) throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        Map<String, Object> input = body(fixture);
        String cover;
        String code;
        switch (kind) {
            case "missing" -> {
                cover = Values.id();
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "foreign" -> {
                cover = asset(account(), "WORK_COVER", "IMAGE");
                code = "ASSET_OWNERSHIP_INVALID";
            }
            case "purpose" -> {
                cover = asset(fixture.owner(), "AVATAR", "IMAGE");
                code = "ASSET_TYPE_MISMATCH";
            }
            case "type" -> {
                cover = asset(fixture.owner(), "WORK_COVER", "VIDEO");
                code = "ASSET_TYPE_MISMATCH";
            }
            default -> {
                cover = asset(fixture.owner(), "WORK_COVER", "IMAGE");
                jdbc.update("UPDATE assets SET cleaned_at = ? WHERE id = ?", Values.now(), cover);
                code = "ASSET_NOT_READY";
            }
        }
        input.put("coverAssetId", cover);

        assertError(save(fixture, "\"v7\"", input), 422, code);

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @Test
    void rejectsMedia() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        Map<String, Object> input = body(fixture);
        input.put("mediaAssetIds", List.of(asset(fixture.owner(), "WORK_MEDIA", "IMAGE")));

        assertError(save(fixture, "\"v7\"", input), 422, "VALIDATION_FAILED");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(List.of(), works.assets(fixture.work().getId()));
    }

    @Test
    void conditionalSql() {
        Fixture fixture = fixture("DRAFT", "TEXT");
        WorkRow changed = works.get(fixture.work().getId());
        changed.setTitle("不能保存的标题");
        changed.setVersion(8);

        assertEquals(0, works.update(changed, 6));

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    @Test
    void rollsBack() throws Exception {
        Fixture fixture = fixture("DRAFT", "IMAGE");
        String old = asset(fixture.owner(), "WORK_MEDIA", "IMAGE");
        String next = asset(fixture.owner(), "WORK_MEDIA", "IMAGE");
        works.bind(fixture.work().getId(), old, 0);
        Map<String, Object> input = body(fixture);
        input.put("mediaAssetIds", List.of(next));
        input.put("coverAssetId", null);
        // 在真实 UPDATE 和媒体关联替换后注入故障，检查整个保存事务回滚。
        doAnswer(invocation -> {
            assertEquals(8L, works.get(fixture.work().getId()).getVersion());
            assertEquals(List.of(next), works.assets(fixture.work().getId()));
            throw new IllegalStateException("Failure after work and media update");
        }).when(views).detail(any(WorkRow.class), any(Viewer.class));

        assertError(save(fixture, "\"v7\"", input), 500, "INTERNAL_ERROR");

        assertEquals(fixture.work(), works.get(fixture.work().getId()));
        assertEquals(List.of(old), works.assets(fixture.work().getId()));
        doCallRealMethod().when(views).detail(any(WorkRow.class), any(Viewer.class));
        saved(save(fixture, "\"v7\"", input), 8);
        assertEquals(List.of(next), works.assets(fixture.work().getId()));
    }

    @Test
    void concurrentSave() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<MockHttpServletResponse>>();
        try {
            for (String title : List.of("并发标题 A", "并发标题 B")) {
                futures.add(pool.submit(() -> {
                    Map<String, Object> input = body(fixture);
                    input.put("title", title);
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return save(fixture, "\"v7\"", input);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var responses = new ArrayList<MockHttpServletResponse>();
            for (Future<MockHttpServletResponse> future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.getStatus() == 200).count());
            assertEquals(1, responses.stream().filter(response -> response.getStatus() == 412).count());
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == 200) {
                    assertEquals(saved(response, 8).get("title"), works.get(fixture.work().getId()).getTitle());
                } else {
                    assertError(response, 412, "VERSION_CONFLICT");
                }
            }
            assertEquals(8L, works.get(fixture.work().getId()).getVersion());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void resave() throws Exception {
        Fixture fixture = fixture("DRAFT", "TEXT");
        saved(save(fixture, "\"v7\"", body(fixture)), 8);
        assertError(save(fixture, "\"v7\"", body(fixture)), 412, "VERSION_CONFLICT");
        Map<String, Object> input = body(fixture);
        input.put("title", "再次保存");

        Map<String, Object> result = saved(save(fixture, "\"v8\"", input), 9);

        assertEquals("再次保存", result.get("title"));
        assertEquals(9L, works.get(fixture.work().getId()).getVersion());
        assertEquals(CREATED, works.get(fixture.work().getId()).getCreatedAt());
    }

    @Test
    void guestService() {
        Fixture fixture = fixture("DRAFT", "TEXT");
        WorkInput input = requests.decode("WorkInput", body(fixture), WorkInput.class);

        ApiException error = assertThrows(ApiException.class,
                () -> service.save(Viewer.guest(), fixture.work().getId(), "\"v7\"", input));

        assertEquals(401, error.status);
        assertEquals("AUTH_REQUIRED", error.code);
        assertEquals(fixture.work(), works.get(fixture.work().getId()));
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '草稿作者', '', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, user, user + "@example.test", "edit_" + user.replace("-", "").substring(0, 12), CREATED);
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

    private Fixture fixture(String status, String kind) {
        Account owner = account();
        String cover = asset(owner, "WORK_COVER", "IMAGE");
        String id = Values.id();
        jdbc.update("""
                INSERT INTO works (id, owner_id, kind, status, version, title, body, cover_asset_id,
                  created_at, updated_at, published_at, failure_code, publish_task_id)
                VALUES (?, ?, ?, ?, 7, '原标题', '原正文', ?, ?, ?, ?, ?, ?)
                """, id, owner.user(), kind, status, cover, CREATED, CREATED + 1000,
                "PUBLISHED".equals(status) ? CREATED + 1000 : null,
                "FAILED".equals(status) ? "MEDIA_PROCESSING_FAILED" : null,
                Set.of("PROCESSING", "FAILED").contains(status) ? "previous-publish-task" : null);
        return new Fixture(owner, works.get(id));
    }

    private String asset(Account owner, String purpose, String type) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, thumbnail_key, size_bytes, created_at)
                VALUES (?, ?, ?, ?, 'READY', ?, ?, ?, 1024, ?)
                """, id, owner.user(), purpose, type, "IMAGE".equals(type) ? "image/jpeg" : "video/mp4",
                "original/" + id, "thumbnails/" + id + ".jpg", CREATED);
        return id;
    }

    private Map<String, Object> body(Fixture fixture) {
        return Values.map("kind", fixture.work().getKind(), "title", "新标题", "body", "新正文",
                "mediaAssetIds", List.of(), "coverAssetId", fixture.work().getCoverAssetId(), "musicLinkId", null);
    }

    private MockHttpServletResponse save(Fixture fixture, String match, Map<String, Object> body) throws Exception {
        return perform(request(fixture.owner().access(), fixture.work().getId(), match, body));
    }

    private MockHttpServletRequestBuilder request(String token, String id, String match, Map<String, Object> body) {
        var request = put(PATH + id).contentType(MediaType.APPLICATION_JSON).content(json.write(body));
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (match != null) request.header("If-Match", match);
        return request;
    }

    private MockHttpServletResponse read(Fixture fixture) throws Exception {
        return perform(get(PATH + fixture.work().getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.owner().access()));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "work-edit-" + Values.id()))
                .andReturn().getResponse();
    }

    private Map<String, Object> saved(MockHttpServletResponse response, long version) throws Exception {
        Map<String, Object> result = data(response, 200);
        assertEquals("\"v" + version + "\"", response.getHeader(HttpHeaders.ETAG));
        assertEquals(version, ((Number) result.get("version")).longValue());
        assertEquals("DRAFT", result.get("status"));
        return result;
    }

    private Map<String, Object> data(MockHttpServletResponse response, int status) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-edit-"));
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
    private record Fixture(Account owner, WorkRow work) {}
}
