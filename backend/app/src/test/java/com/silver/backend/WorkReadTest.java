package com.silver.backend;

import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
@TestPropertySource(properties = "htw.public-api-base-url=https://api.example.test/api/v1")
class WorkReadTest extends BackendTestSupport {
    private static final String PATH = "/api/v1/works/";
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;
    private static final Set<String> DETAIL_FIELDS = Set.of(
            "id", "kind", "status", "version", "title", "excerpt", "author", "cover", "stats",
            "viewer", "createdAt", "publishedAt", "body", "assets", "coverAsset", "musicLink",
            "updatedAt", "failureCode");
    private static final Set<String> ASSET_FIELDS = Set.of(
            "id", "purpose", "mediaType", "status", "mimeType", "sizeBytes", "url", "thumbnailUrl",
            "width", "height", "durationMs", "failureCode", "createdAt");

    @Autowired private MockMvc mvc;
    @Autowired private AuthMapper users;
    @Autowired private WorkMapper works;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;

    private final List<String> userIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();
    private final List<String> musicIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String work : workIds) {
            jdbc.update("DELETE FROM work_likes WHERE work_id = ?", work);
            jdbc.update("DELETE FROM bookmarks WHERE work_id = ?", work);
            jdbc.update("DELETE FROM comments WHERE work_id = ?", work);
            jdbc.update("DELETE FROM work_assets WHERE work_id = ?", work);
            jdbc.update("DELETE FROM works WHERE id = ?", work);
        }
        for (String asset : assetIds) jdbc.update("DELETE FROM assets WHERE id = ?", asset);
        for (String link : musicIds) jdbc.update("DELETE FROM music_links WHERE id = ?", link);
        for (String user : userIds) {
            jdbc.update("DELETE FROM sessions WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "owner", "other"})
    void published(String role) throws Exception {
        String owner = user();
        WorkRow work = work(owner, "PUBLISHED", "TEXT");
        String token = token(role, owner);

        MockHttpServletResponse response = read(work.getId(), token);

        Map<String, Object> result = data(response);
        assertDetail(result, work);
        assertEquals(Map.of("liked", false, "bookmarked", false, "isOwner", "owner".equals(role)),
                result.get("viewer"));
        assertEquals("\"v7\"", response.getHeader(HttpHeaders.ETAG));
        assertEquals(work, works.get(work.getId()), "读取不应更新作品或版本");
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT,guest", "DRAFT,owner", "DRAFT,other",
            "PROCESSING,guest", "PROCESSING,owner", "PROCESSING,other",
            "FAILED,guest", "FAILED,owner", "FAILED,other"
    })
    void nonpublic(String status, String role) throws Exception {
        String owner = user();
        WorkRow work = work(owner, status, "TEXT");

        MockHttpServletResponse response = read(work.getId(), token(role, owner));

        if ("owner".equals(role)) {
            Map<String, Object> result = data(response);
            assertDetail(result, work);
            assertEquals(Map.of("liked", false, "bookmarked", false, "isOwner", true), result.get("viewer"));
            assertEquals("\"v7\"", response.getHeader(HttpHeaders.ETAG));
            assertNull(result.get("publishedAt"));
        } else {
            assertError(response, 404, "WORK_NOT_FOUND");
        }
        assertEquals(work, works.get(work.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingWork(boolean authenticated) throws Exception {
        String token = authenticated ? identity(user()).access() : null;

        assertError(read(Values.id(), token), 404, "WORK_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "expired", "revoked", "missingSession", "wrongUser"})
    void badBearer(String kind) throws Exception {
        String owner = user();
        WorkRow work = work(owner, "PUBLISHED", "TEXT");
        Identity login = identity(user());
        String token = login.access();
        String code = "SESSION_REVOKED";
        switch (kind) {
            case "invalid" -> {
                token = "invalid-token";
                code = "TOKEN_INVALID";
            }
            case "expired" -> {
                token = crypto.jwt(login.session().getUserId(), login.session().getId(), -1);
                code = "ACCESS_TOKEN_EXPIRED";
            }
            case "revoked" -> jdbc.update("UPDATE sessions SET revoked = TRUE WHERE id = ?", login.session().getId());
            case "missingSession" -> jdbc.update("DELETE FROM sessions WHERE id = ?", login.session().getId());
            case "wrongUser" -> token = crypto.jwt(owner, login.session().getId(), 900);
            default -> throw new IllegalArgumentException(kind);
        }

        assertError(read(work.getId(), token), 401, code);

        assertDetail(data(read(work.getId(), null)), work);
        assertEquals(work, works.get(work.getId()));
    }

    @Test
    void interactions() throws Exception {
        String owner = user();
        String actor = user();
        String other = user();
        WorkRow work = work(owner, "PUBLISHED", "TEXT");
        Identity login = identity(actor);
        jdbc.update("INSERT INTO work_likes (work_id, user_id, created_at) VALUES (?, ?, ?), (?, ?, ?)",
                work.getId(), actor, CREATED, work.getId(), other, CREATED);
        jdbc.update("INSERT INTO bookmarks (id, work_id, user_id, created_at) VALUES (?, ?, ?, ?)",
                Values.id(), work.getId(), actor, CREATED);
        for (String user : List.of(actor, other)) {
            jdbc.update("INSERT INTO comments (id, work_id, user_id, body, created_at) VALUES (?, ?, ?, '评论', ?)",
                    Values.id(), work.getId(), user, CREATED);
        }

        Map<String, Object> current = data(read(work.getId(), login.access()));

        assertEquals(Map.of("liked", true, "bookmarked", true, "isOwner", false), current.get("viewer"));
        assertStats(object(current.get("stats")), 2, 1, 2);
        Map<String, Object> guest = data(read(work.getId(), null));
        assertEquals(Map.of("liked", false, "bookmarked", false, "isOwner", false), guest.get("viewer"));
        assertStats(object(guest.get("stats")), 2, 1, 2);

        jdbc.update("DELETE FROM work_likes WHERE work_id = ? AND user_id = ?", work.getId(), actor);
        jdbc.update("DELETE FROM bookmarks WHERE work_id = ? AND user_id = ?", work.getId(), actor);
        Map<String, Object> changed = data(read(work.getId(), login.access()));
        assertEquals(Map.of("liked", false, "bookmarked", false, "isOwner", false), changed.get("viewer"));
        assertStats(object(changed.get("stats")), 1, 0, 2);
        assertEquals(work, works.get(work.getId()));
    }

    @ParameterizedTest
    @CsvSource({"PUBLISHED,guest,public", "DRAFT,owner,private"})
    void mediaUrls(String status, String role, String scope) throws Exception {
        String owner = user();
        WorkRow work = work(owner, status, "IMAGE");
        String image = asset(owner, "WORK_MEDIA", "IMAGE");
        attach(work, image);

        Map<String, Object> result = data(read(work.getId(), token(role, owner)));

        assertDetail(result, work);
        List<?> media = (List<?>) result.get("assets");
        assertEquals(1, media.size());
        Map<String, Object> item = object(media.get(0));
        assertEquals(ASSET_FIELDS, item.keySet());
        assertEquals(url(scope, image, false), item.get("url"));
        assertEquals(url(scope, image, true), item.get("thumbnailUrl"));
        assertEquals(url(scope, image, true), object(result.get("cover")).get("url"));
        assertNull(result.get("coverAsset"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE", "VIDEO", "AUDIO", "MUSIC_LINK"})
    void workKinds(String kind) throws Exception {
        String owner = user();
        WorkRow work = work(owner, "PUBLISHED", kind);
        if ("MUSIC_LINK".equals(kind)) {
            String music = Values.id();
            jdbc.update("""
                    INSERT INTO music_links (id, owner_id, provider, source_url, metadata_status, created_at)
                    VALUES (?, ?, 'NETEASE', 'https://music.163.com/song?id=123', 'PARTIAL', ?)
                    """, music, owner, CREATED);
            musicIds.add(music);
            jdbc.update("UPDATE works SET music_link_id = ? WHERE id = ?", music, work.getId());
        } else {
            attach(work, asset(owner, "WORK_MEDIA", kind));
        }
        work = works.get(work.getId());

        Map<String, Object> result = data(read(work.getId(), null));

        assertDetail(result, work);
        if ("MUSIC_LINK".equals(kind)) {
            Map<String, Object> link = object(result.get("musicLink"));
            assertEquals(Set.of("id", "provider", "sourceUrl", "title", "artist", "coverUrl",
                    "metadataStatus", "playback"), link.keySet());
            assertEquals(work.getMusicLinkId(), link.get("id"));
            assertEquals("PARTIAL", link.get("metadataStatus"));
            assertEquals("EXTERNAL", object(link.get("playback")).get("mode"));
            assertNull(object(link.get("playback")).get("embedUrl"));
            assertNull(link.get("title"));
            assertNull(link.get("artist"));
            assertNull(link.get("coverUrl"));
            assertEquals(List.of(), result.get("assets"));
        } else {
            Map<String, Object> item = object(((List<?>) result.get("assets")).get(0));
            assertEquals(ASSET_FIELDS, item.keySet());
            assertEquals(kind, item.get("mediaType"));
            assertNull(result.get("musicLink"));
            if ("AUDIO".equals(kind)) {
                assertNull(result.get("cover"));
                assertNull(item.get("thumbnailUrl"));
                assertNull(item.get("width"));
                assertNull(item.get("height"));
                assertEquals(12_345L, ((Number) item.get("durationMs")).longValue());
            } else {
                assertNotNull(result.get("cover"));
                assertTrue(((String) object(result.get("cover")).get("url")).endsWith("/thumbnail"));
            }
        }
    }

    @Test
    void explicitCover() throws Exception {
        String owner = user();
        WorkRow work = work(owner, "PUBLISHED", "IMAGE");
        String main = asset(owner, "WORK_MEDIA", "IMAGE");
        String cover = asset(owner, "WORK_COVER", "IMAGE");
        attach(work, main);
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", cover, work.getId());

        Map<String, Object> result = data(read(work.getId(), null));

        assertEquals(url("public", cover, true), object(result.get("cover")).get("url"));
        assertEquals(cover, object(result.get("coverAsset")).get("id"));
        List<?> media = (List<?>) result.get("assets");
        assertEquals(1, media.size());
        assertEquals(main, object(media.get(0)).get("id"));
    }

    @Test
    void cookieNotBearer() throws Exception {
        String owner = user();
        WorkRow work = work(owner, "DRAFT", "TEXT");
        Identity login = identity(owner);

        MockHttpServletResponse response = perform(get(PATH + work.getId()).cookie(
                new Cookie("htw_refresh", login.refresh()), new Cookie("htw_media", login.media())));

        assertError(response, 404, "WORK_NOT_FOUND");
        assertDetail(data(read(work.getId(), login.access())), work);
    }

    @Test
    void versionChanges() throws Exception {
        WorkRow work = work(user(), "PUBLISHED", "TEXT");
        MockHttpServletResponse first = read(work.getId(), null);
        assertEquals("\"v7\"", first.getHeader(HttpHeaders.ETAG));

        jdbc.update("UPDATE works SET version = 8, title = '更新标题' WHERE id = ?", work.getId());
        MockHttpServletResponse next = read(work.getId(), null);

        Map<String, Object> result = data(next);
        assertEquals("\"v8\"", next.getHeader(HttpHeaders.ETAG));
        assertEquals(8L, ((Number) result.get("version")).longValue());
        assertEquals("更新标题", result.get("title"));
        assertEquals(8L, works.get(work.getId()).getVersion());
    }

    private String user() {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '作品作者', '作者简介',
                  TRUE, 'DARK', 'REDUCED', TRUE, ?)
                """, id, id + "@example.test", "read_" + id.replace("-", "").substring(0, 12), CREATED);
        userIds.add(id);
        return id;
    }

    private WorkRow work(String owner, String status, String kind) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO works (id, owner_id, kind, status, version, title, body,
                  created_at, updated_at, published_at, failure_code, publish_task_id)
                VALUES (?, ?, ?, ?, 7, '作品标题', '作品正文', ?, ?, ?, ?, ?)
                """, id, owner, kind, status, CREATED, CREATED + 1000,
                "PUBLISHED".equals(status) ? CREATED + 2000 : null,
                "FAILED".equals(status) ? "MEDIA_PROCESSING_FAILED" : null,
                "PROCESSING".equals(status) ? "internal-publish-task" : null);
        workIds.add(id);
        return works.get(id);
    }

    private Identity identity(String owner) {
        String refresh = crypto.randomToken();
        var row = new SessionRow();
        row.setId(Values.id());
        row.setUserId(owner);
        row.setRefreshHash(crypto.digest(refresh));
        row.setCreatedAt(Values.now());
        row.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(row);
        String media = crypto.ticket("media-read", Map.of("user", owner, "session", row.getId()), 86_400_000);
        return new Identity(crypto.jwt(owner, row.getId(), 900), refresh, media, row);
    }

    private String token(String role, String owner) {
        return switch (role) {
            case "guest" -> null;
            case "owner" -> identity(owner).access();
            case "other" -> identity(user()).access();
            default -> throw new IllegalArgumentException(role);
        };
    }

    private String asset(String owner, String purpose, String type) {
        String id = Values.id();
        boolean audio = "AUDIO".equals(type);
        String mime = audio ? "audio/flac" : "VIDEO".equals(type) ? "video/mp4" : "image/jpeg";
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, playback_key, thumbnail_key, size_bytes, created_at, width, height, duration_ms)
                VALUES (?, ?, ?, ?, 'READY', ?, ?, ?, ?, 4096, ?, ?, ?, ?)
                """, id, owner, purpose, type, mime, "original/" + id,
                "IMAGE".equals(type) ? null : "processed/" + id + (audio ? ".mp3" : ".mp4"),
                audio ? null : "thumbnails/" + id + ".jpg", CREATED,
                audio ? null : 1280, audio ? null : 720, "IMAGE".equals(type) ? null : 12_345L);
        assetIds.add(id);
        return id;
    }

    private void attach(WorkRow work, String asset) {
        jdbc.update("INSERT INTO work_assets (work_id, asset_id, position) VALUES (?, ?, 0)", work.getId(), asset);
    }

    private MockHttpServletResponse read(String work, String token) throws Exception {
        var request = get(PATH + work);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return perform(request);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Request-Id", "work-read-" + Values.id()))
                .andReturn().getResponse();
    }

    private Map<String, Object> data(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(response.getContentType())));
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("data", "requestId"), body.keySet());
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-read-"));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        Map<String, Object> result = object(body.get("data"));
        assertEquals("\"v" + ((Number) result.get("version")).longValue() + "\"",
                response.getHeader(HttpHeaders.ETAG));
        return result;
    }

    private void assertDetail(Map<String, Object> data, WorkRow work) {
        assertEquals(DETAIL_FIELDS, data.keySet());
        assertEquals(work.getId(), data.get("id"));
        assertEquals(work.getKind(), data.get("kind"));
        assertEquals(work.getStatus(), data.get("status"));
        assertEquals(work.getVersion(), ((Number) data.get("version")).longValue());
        assertEquals(work.getTitle(), data.get("title"));
        assertEquals(work.getBody(), data.get("body"));
        assertEquals("作品正文", data.get("excerpt"));
        assertEquals("2023-11-14T22:13:20Z", data.get("createdAt"));
        assertEquals("2023-11-14T22:13:21Z", data.get("updatedAt"));
        assertEquals("PUBLISHED".equals(work.getStatus()) ? "2023-11-14T22:13:22Z" : null, data.get("publishedAt"));
        assertEquals(work.getFailureCode(), data.get("failureCode"));
        Map<String, Object> author = object(data.get("author"));
        assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl", "publishedWorkCount", "createdAt"),
                author.keySet());
        assertEquals(work.getOwnerId(), author.get("id"));
        assertEquals("作品作者", author.get("displayName"));
        assertEquals(Set.of("likeCount", "bookmarkCount", "commentCount"), object(data.get("stats")).keySet());
        assertEquals(Set.of("liked", "bookmarked", "isOwner"), object(data.get("viewer")).keySet());
        assertInstanceOf(List.class, data.get("assets"));
    }

    private void assertStats(Map<String, Object> stats, long likes, long bookmarks, long comments) {
        assertEquals(likes, ((Number) stats.get("likeCount")).longValue());
        assertEquals(bookmarks, ((Number) stats.get("bookmarkCount")).longValue());
        assertEquals(comments, ((Number) stats.get("commentCount")).longValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        Map<String, Object> body = json.readMap(response.getContentAsString());
        assertEquals(Set.of("code", "message", "requestId", "details"), body.keySet());
        assertEquals(code, body.get("code"));
        assertEquals(response.getHeader("X-Request-Id"), body.get("requestId"));
        assertTrue(((String) body.get("requestId")).startsWith("work-read-"));
        if (status == 404) assertEquals("作品不存在", body.get("message"));
        assertEquals(List.of(), body.get("details"));
        assertNull(response.getHeader(HttpHeaders.ETAG));
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private String url(String scope, String asset, boolean thumbnail) {
        return BASE + "/media/" + scope + "/assets/" + asset + (thumbnail ? "/thumbnail" : "/file");
    }

    private record Identity(String access, String refresh, String media, SessionRow session) {}
}
