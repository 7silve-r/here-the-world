package com.silver.backend;

import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaReadTest extends BackendTestSupport {
    private static final Path ROOT = Path.of("target", "media-read-" + Values.id()).toAbsolutePath().normalize();
    private static final byte[] ORIGINAL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+-"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PLAYBACK = "processed-media-content".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] THUMBNAIL = "jpeg-thumbnail-content".getBytes(StandardCharsets.US_ASCII);
    private static final long MODIFIED = 1_700_000_000_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("htw.storage-root", ROOT::toString);
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AssetMapper assets;
    @Autowired private AuthMapper users;
    @Autowired private AuthService auth;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private FileStorage storage;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();
    private final List<Path> outsideFiles = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        for (String user : userIds) jdbc.update("UPDATE users SET avatar_asset_id=NULL WHERE id=?", user);
        for (String work : workIds) {
            jdbc.update("DELETE FROM work_assets WHERE work_id=?", work);
            jdbc.update("DELETE FROM works WHERE id=?", work);
        }
        for (String user : userIds) {
            jdbc.update("DELETE FROM assets WHERE owner_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
        clearFiles(false);
        for (Path path : outsideFiles) Files.deleteIfExists(path);
    }

    @AfterAll
    static void removeRoot() throws IOException {
        clearFiles(true);
    }

    private static void clearFiles(boolean includeRoot) throws IOException {
        assertEquals(Path.of("target").toAbsolutePath().normalize(), ROOT.getParent());
        if (!Files.exists(ROOT)) return;
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (includeRoot || !path.equals(ROOT)) Files.deleteIfExists(path);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar", "cover", "attachment"})
    void publicRead(String reference) throws Exception {
        Media media = media(account());
        publish(media, reference);
        HttpResponse<byte[]> response = send("GET", path(media, false, "file"));
        content(response, 200, ORIGINAL, "image/png", false);
        assertEquals("bytes", header(response, HttpHeaders.ACCEPT_RANGES));
        assertEquals(Long.toString(ORIGINAL.length), header(response, HttpHeaders.CONTENT_LENGTH));
        assertEquals(MODIFIED, modified(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "cookie"})
    void privateRead(String credential) throws Exception {
        Media media = media(account());
        var response = read(media, true, "file", media.owner(), credential);
        content(response, 200, ORIGINAL, "image/png", true);
        assertTrue(response.headers().allValues(HttpHeaders.SET_COOKIE).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void requiresLogin(String method) throws Exception {
        Media media = media(account());
        HttpResponse<byte[]> response = send(method, path(media, true, "file"));
        assertEquals(401, response.statusCode());
        if ("GET".equals(method)) error(response, 401, "AUTH_REQUIRED");
        else assertEquals(0, response.body().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "cookie"})
    void ownerOnly(String credential) throws Exception {
        Media media = media(account());
        Account other = account();
        error(read(media, true, "file", other, credential), 404, "ASSET_NOT_FOUND");
        publish(media, "attachment");
        // 公开引用不会把私有入口变成任何已登录用户都能读取的入口。
        error(read(media, true, "file", other, credential), 404, "ASSET_NOT_FOUND");
        content(send("GET", path(media, false, "file")), 200, ORIGINAL, "image/png", false);
    }

    @ParameterizedTest
    @MethodSource("notReadyCases")
    void requiresReady(String state, boolean privateMedia) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        jdbc.update("UPDATE assets SET status=? WHERE id=?", state, media.id());
        error(read(media, privateMedia, "file", media.owner(), "cookie"), 404, "ASSET_NOT_FOUND");
    }

    static Stream<Arguments> notReadyCases() {
        return Stream.of("CREATED", "UPLOADED", "PROCESSING", "FAILED").flatMap(state ->
                Stream.of(Arguments.of(state, false), Arguments.of(state, true)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingFiles(boolean privateMedia) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        error(read(media, privateMedia, "thumbnail", media.owner(), "cookie"), 404, "ASSET_NOT_FOUND");
        error(read(media, privateMedia, "unknown", media.owner(), "cookie"), 404, "ASSET_NOT_FOUND");
        Files.delete(storage.path(media.key()));
        error(read(media, privateMedia, "file", media.owner(), "cookie"), 404, "ASSET_NOT_FOUND");
        Files.createDirectory(storage.path(media.key()));
        error(read(media, privateMedia, "file", media.owner(), "cookie"), 404, "ASSET_NOT_FOUND");
    }

    @Test
    void unknownAsset() throws Exception {
        Account owner = account();
        error(send("GET", "/api/v1/media/public/assets/missing/file"), 404, "ASSET_NOT_FOUND");
        error(send("GET", "/api/v1/media/private/assets/missing/file", cookie(owner)), 404, "ASSET_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"avatar", "cover", "attachment"})
    void withdrawPublic(String reference) throws Exception {
        Media media = media(account());
        String work = publish(media, reference);
        HttpResponse<byte[]> first = send("GET", path(media, false, "file"));
        assertEquals(200, first.statusCode());
        if ("avatar".equals(reference)) {
            jdbc.update("UPDATE users SET avatar_asset_id=NULL WHERE id=?", media.owner().user());
        } else {
            jdbc.update("DELETE FROM work_assets WHERE work_id=?", work);
            jdbc.update("DELETE FROM works WHERE id=?", work);
        }
        // 条件请求仍要先检查当前公开引用，不能直接凭 Last-Modified 返回 304。
        error(send("GET", path(media, false, "file"), HttpHeaders.IF_MODIFIED_SINCE,
                header(first, HttpHeaders.LAST_MODIFIED)), 404, "ASSET_NOT_FOUND");
        content(read(media, true, "file", media.owner(), "cookie"), 200, ORIGINAL, "image/png", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PROCESSING", "FAILED"})
    void unpublishedWork(String state) throws Exception {
        Media media = media(account());
        String work = publish(media, "attachment");
        jdbc.update("UPDATE works SET status=? WHERE id=?", state, work);
        error(send("GET", path(media, false, "file")), 404, "ASSET_NOT_FOUND");
    }

    @Test
    void bearerPriority() throws Exception {
        Media media = media(account());
        Account other = account();
        error(send("GET", path(media, true, "file"), HttpHeaders.COOKIE, "htw_media=" + media.owner().media(), HttpHeaders.AUTHORIZATION,
                "Bearer invalid"), 401, "TOKEN_INVALID");
        error(send("GET", path(media, true, "file"), HttpHeaders.COOKIE, "htw_media=" + media.owner().media(), HttpHeaders.AUTHORIZATION,
                "Bearer " + other.access()), 404, "ASSET_NOT_FOUND");
        content(send("GET", path(media, true, "file"), HttpHeaders.COOKIE, "htw_media=invalid",
                HttpHeaders.AUTHORIZATION, "Bearer " + media.owner().access()), 200, ORIGINAL, "image/png", true);
    }

    @Test
    void publicIgnoresCookie() throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        content(send("GET", path(media, false, "file"), HttpHeaders.COOKIE, "htw_media=invalid"),
                200, ORIGINAL, "image/png", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "POST"})
    void cookieScope(String method) throws Exception {
        Media media = media(account());
        String route = switch (method) {
            case "GET" -> "/api/v1/me";
            case "PUT" -> "/api/v1/works/missing/like";
            default -> path(media, true, "file");
        };
        error(send(method, route, cookie(media.owner())), 401, "AUTH_REQUIRED");
    }

    @ParameterizedTest
    @MethodSource("badTickets")
    void invalidTicket(String type) throws Exception {
        Media media = media(account());
        Account owner = media.owner();
        Map<String, Object> payload = Values.map("user", owner.user(), "session", owner.session());
        String ticket;
        switch (type) {
            case "empty" -> ticket = "";
            case "malformed" -> ticket = "invalid";
            case "expired" -> ticket = crypto.ticket("media-read", payload, -1);
            case "purpose" -> ticket = crypto.ticket("upload", payload, 60_000);
            case "tampered" -> ticket = "x" + owner.media().substring(1);
            default -> {
                switch (type) {
                    case "missing-user" -> payload.remove("user");
                    case "missing-session" -> payload.remove("session");
                    case "number-user" -> payload.put("user", 1);
                    case "number-session" -> payload.put("session", 1);
                    case "blank-user" -> payload.put("user", " ");
                    case "blank-session" -> payload.put("session", " ");
                    default -> throw new IllegalArgumentException(type);
                }
                ticket = crypto.ticket("media-read", payload, 60_000);
            }
        }
        error(send("GET", path(media, true, "file"), HttpHeaders.COOKIE, "htw_media=" + ticket),
                401, "TOKEN_INVALID");
    }

    static Stream<String> badTickets() {
        return Stream.of("empty", "malformed", "expired", "purpose", "tampered", "missing-user",
                "missing-session", "number-user", "number-session", "blank-user", "blank-session");
    }

    @ParameterizedTest
    @MethodSource("sessionCases")
    void invalidSession(String state, String credential) throws Exception {
        Media media = media(account());
        Account owner = media.owner();
        switch (state) {
            case "revoked" -> jdbc.update("UPDATE sessions SET revoked=TRUE WHERE id=?", owner.session());
            case "expired" -> jdbc.update("UPDATE sessions SET expires_at=? WHERE id=?", Values.now() - 1, owner.session());
            case "missing" -> jdbc.update("DELETE FROM sessions WHERE id=?", owner.session());
            case "other-user" -> jdbc.update("UPDATE sessions SET user_id=? WHERE id=?", account().user(), owner.session());
            default -> throw new IllegalArgumentException(state);
        }
        error(read(media, true, "file", owner, credential), 401, "SESSION_REVOKED");
    }

    static Stream<Arguments> sessionCases() {
        return Stream.of("revoked", "expired", "missing", "other-user").flatMap(state ->
                Stream.of(Arguments.of(state, "cookie"), Arguments.of(state, "bearer")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "bearer"})
    void logoutRevokes(String credential) throws Exception {
        Media media = media(account());
        assertEquals(200, read(media, true, "file", media.owner(), credential).statusCode());
        auth.logout(media.owner().refresh());
        error(read(media, true, "file", media.owner(), credential), 401, "SESSION_REVOKED");
    }

    @ParameterizedTest
    @CsvSource({"VIDEO,video/webm,mp4,video/mp4", "AUDIO,audio/ogg,mp3,audio/mpeg"})
    void playback(String type, String originalMime, String extension, String mime) throws Exception {
        Media media = media(account());
        AssetRow row = assets.asset(media.id());
        row.setMediaType(type);
        row.setMimeType(originalMime);
        row.setPlaybackKey("processed/" + media.id() + "." + extension);
        assets.updateAsset(row);
        write(row.getPlaybackKey(), PLAYBACK);
        content(read(media, true, "file", media.owner(), "cookie"), 200, PLAYBACK, mime, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void thumbnail(boolean privateMedia) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        AssetRow row = assets.asset(media.id());
        row.setThumbnailKey("thumbnails/" + media.id() + ".jpg");
        assets.updateAsset(row);
        write(row.getThumbnailKey(), THUMBNAIL);
        content(read(media, privateMedia, "thumbnail", media.owner(), "cookie"),
                200, THUMBNAIL, "image/jpeg", privateMedia);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void head(boolean privateMedia) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        HttpResponse<byte[]> response = send("HEAD", path(media, privateMedia, "file"),
                privateMedia ? cookie(media.owner()) : new String[0]);
        content(response, 200, new byte[0], "image/png", privateMedia);
        assertEquals(Long.toString(ORIGINAL.length), header(response, HttpHeaders.CONTENT_LENGTH));
        assertEquals(MODIFIED, modified(response));
        assertEquals("bytes", header(response, HttpHeaders.ACCEPT_RANGES));
    }

    @ParameterizedTest
    @MethodSource("rangeCases")
    void range(String value, int from, int to, boolean privateMedia) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        HttpResponse<byte[]> response = send("GET", path(media, privateMedia, "file"),
                HttpHeaders.RANGE, value, HttpHeaders.COOKIE, "htw_media=" + media.owner().media());
        content(response, 206, Arrays.copyOfRange(ORIGINAL, from, to + 1), "image/png", privateMedia);
        assertEquals("bytes " + from + "-" + to + "/" + ORIGINAL.length, header(response, HttpHeaders.CONTENT_RANGE));
        assertEquals(Integer.toString(to - from + 1), header(response, HttpHeaders.CONTENT_LENGTH));
    }

    static Stream<Arguments> rangeCases() {
        return Stream.of(false, true).flatMap(privateMedia -> Stream.of(
                Arguments.of("bytes=0-7", 0, 7, privateMedia),
                Arguments.of("bytes=8-", 8, ORIGINAL.length - 1, privateMedia),
                Arguments.of("bytes=-5", ORIGINAL.length - 5, ORIGINAL.length - 1, privateMedia),
                Arguments.of("bytes=0-9999", 0, ORIGINAL.length - 1, privateMedia)));
    }

    @ParameterizedTest
    @CsvSource({"bytes=9999-", "bytes=9-2", "bytes=a-b", "invalid"})
    void badRange(String value) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        HttpResponse<byte[]> response = send("GET", path(media, false, "file"), HttpHeaders.RANGE, value);
        assertEquals(416, response.statusCode());
        assertEquals("bytes */" + ORIGINAL.length, header(response, HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void multiRange() throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        HttpResponse<byte[]> response = send("GET", path(media, false, "file"), HttpHeaders.RANGE, "bytes=0-2,8-10");
        assertEquals(206, response.statusCode());
        assertTrue(header(response, HttpHeaders.CONTENT_TYPE).startsWith("multipart/byteranges;"));
        String body = new String(response.body(), StandardCharsets.US_ASCII);
        assertTrue(body.contains("Content-Range: bytes 0-2/" + ORIGINAL.length));
        assertTrue(body.contains("Content-Range: bytes 8-10/" + ORIGINAL.length));
        assertTrue(body.contains("\r\n\r\n012\r\n"));
        assertTrue(body.contains("\r\n\r\n89A\r\n"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notModified(boolean privateMedia) throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        HttpResponse<byte[]> first = read(media, privateMedia, "file", media.owner(), "cookie");
        String last = header(first, HttpHeaders.LAST_MODIFIED);
        HttpResponse<byte[]> response = send("GET", path(media, privateMedia, "file"),
                HttpHeaders.IF_MODIFIED_SINCE, last, HttpHeaders.COOKIE, "htw_media=" + media.owner().media());
        assertEquals(304, response.statusCode());
        assertEquals(0, response.body().length);
        cache(response, privateMedia);
        HttpResponse<byte[]> head = send("HEAD", path(media, privateMedia, "file"),
                HttpHeaders.IF_MODIFIED_SINCE, last, HttpHeaders.COOKIE, "htw_media=" + media.owner().media());
        assertEquals(304, head.statusCode());
        assertEquals(0, head.body().length);
    }

    @Test
    void modifiedAgain() throws Exception {
        Media media = media(account());
        publish(media, "attachment");
        String last = header(send("GET", path(media, false, "file")), HttpHeaders.LAST_MODIFIED);
        write(media.key(), PLAYBACK);
        Files.setLastModifiedTime(storage.path(media.key()), FileTime.fromMillis(MODIFIED + 10_000));
        HttpResponse<byte[]> response = send("GET", path(media, false, "file"), HttpHeaders.IF_MODIFIED_SINCE, last);
        content(response, 200, PLAYBACK, "image/png", false);
        assertEquals(MODIFIED + 10_000, modified(response));
    }

    @Test
    void conditionNeedsAuth() throws Exception {
        Media media = media(account());
        String last = header(read(media, true, "file", media.owner(), "cookie"), HttpHeaders.LAST_MODIFIED);
        error(send("GET", path(media, true, "file"), HttpHeaders.IF_MODIFIED_SINCE, last), 401, "AUTH_REQUIRED");
        error(send("GET", path(media, true, "file"), HttpHeaders.IF_MODIFIED_SINCE, last,
                HttpHeaders.COOKIE, "htw_media=" + account().media()), 404, "ASSET_NOT_FOUND");
    }

    @Test
    void headRange() throws Exception {
        Media media = media(account());
        HttpResponse<byte[]> response = send("HEAD", path(media, true, "file"),
                HttpHeaders.RANGE, "bytes=0-3", HttpHeaders.COOKIE, "htw_media=" + media.owner().media());
        assertEquals(206, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals("4", header(response, HttpHeaders.CONTENT_LENGTH));
    }

    @Test
    void pathEscape() throws Exception {
        Media media = media(account());
        Path outside = ROOT.getParent().resolve("read-secret-" + Values.id() + ".bin");
        outsideFiles.add(outside);
        Files.writeString(outside, "private-file-outside-storage");
        jdbc.update("UPDATE assets SET storage_key=? WHERE id=?", "../" + outside.getFileName(), media.id());
        error(read(media, true, "file", media.owner(), "cookie"), 500, "INTERNAL_ERROR");
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users(id,email,username,password_hash,display_name,bio,email_verified,theme,motion,auto_play_media,created_at)
                VALUES(?,?,?,'private-password-hash','读取用户','',TRUE,'SYSTEM','SYSTEM',TRUE,?)
                """, user, user + "@example.test", "read_" + user.substring(0, 8), Values.now());
        userIds.add(user);
        String refresh = crypto.randomToken();
        SessionRow session = new SessionRow();
        session.setId(Values.id());
        session.setUserId(user);
        session.setRefreshHash(crypto.digest(refresh));
        session.setCreatedAt(Values.now());
        session.setExpiresAt(Values.now() + 86_400_000);
        users.insertSession(session);
        return new Account(user, session.getId(), crypto.jwt(user, session.getId(), 900),
                crypto.ticket("media-read", Values.map("user", user, "session", session.getId()), 86_400_000), refresh);
    }

    private Media media(Account owner) throws IOException {
        AssetRow a = new AssetRow();
        a.setId(Values.id());
        a.setOwnerId(owner.user());
        a.setPurpose("WORK_MEDIA");
        a.setMediaType("IMAGE");
        a.setMimeType("image/png");
        a.setStatus("READY");
        a.setStorageKey("originals/" + a.getId());
        a.setSizeBytes(ORIGINAL.length);
        a.setCreatedAt(Values.now());
        assets.insertAsset(a);
        write(a.getStorageKey(), ORIGINAL);
        return new Media(owner, a.getId(), a.getStorageKey());
    }

    private void write(String key, byte[] bytes) throws IOException {
        Path path = storage.output(key);
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, FileTime.fromMillis(MODIFIED));
    }

    private String publish(Media media, String reference) {
        if ("avatar".equals(reference)) {
            jdbc.update("UPDATE users SET avatar_asset_id=? WHERE id=?", media.id(), media.owner().user());
            return null;
        }
        String work = Values.id();
        workIds.add(work);
        jdbc.update("""
                INSERT INTO works(id,owner_id,kind,status,version,title,body,cover_asset_id,created_at,updated_at)
                VALUES(?,?,'IMAGE','PUBLISHED',1,'media','',?,?,?)
                """, work, media.owner().user(), "cover".equals(reference) ? media.id() : null, Values.now(), Values.now());
        if ("attachment".equals(reference)) {
            jdbc.update("INSERT INTO work_assets(work_id,asset_id,position) VALUES(?,?,0)", work, media.id());
        }
        return work;
    }

    private String path(Media media, boolean privateMedia, String variant) {
        return "/api/v1/media/" + (privateMedia ? "private" : "public") + "/assets/" + media.id() + "/" + variant;
    }

    private String[] cookie(Account owner) {
        return new String[] {HttpHeaders.COOKIE, "htw_media=" + owner.media()};
    }

    private HttpResponse<byte[]> read(Media media, boolean privateMedia, String variant, Account owner, String credential)
            throws Exception {
        return send("GET", path(media, privateMedia, variant), "cookie".equals(credential) ? cookie(owner)
                : new String[] {HttpHeaders.AUTHORIZATION, "Bearer " + owner.access()});
    }

    private HttpResponse<byte[]> send(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody())
                .header("X-Request-ID", "read-" + Values.id());
        if (headers.length > 0) request.headers(headers);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String header(HttpResponse<byte[]> response, String name) {
        return response.headers().firstValue(name).orElseThrow(() -> new AssertionError("缺少响应头: " + name));
    }

    private long modified(HttpResponse<byte[]> response) {
        return ZonedDateTime.parse(header(response, HttpHeaders.LAST_MODIFIED), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli();
    }

    private void cache(HttpResponse<byte[]> response, boolean privateMedia) {
        assertEquals(List.of(privateMedia ? "private, no-store" : "public, max-age=60, must-revalidate"),
                response.headers().allValues(HttpHeaders.CACHE_CONTROL));
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
    }

    private void content(HttpResponse<byte[]> response, int status, byte[] bytes, String mime, boolean privateMedia) {
        assertEquals(status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertArrayEquals(bytes, response.body());
        assertEquals(mime, header(response, HttpHeaders.CONTENT_TYPE));
        assertTrue(header(response, "X-Request-ID").startsWith("read-"));
        cache(response, privateMedia);
    }

    private void error(HttpResponse<byte[]> response, int status, String code) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertEquals(status, response.statusCode(), body);
        assertEquals(code, json.readMap(body).get("code"));
        assertFalse(body.contains("private-file-outside-storage"));
        assertEquals(List.of("private, no-store"), response.headers().allValues(HttpHeaders.CACHE_CONTROL));
    }

    private record Account(String user, String session, String access, String media, String refresh) {}
    private record Media(Account owner, String id, String key) {}
}
