package com.silver.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silver.backend.business.dto.MusicLink;
import com.silver.backend.business.dto.MusicLinkInput;
import com.silver.backend.business.music.MusicService;
import com.silver.backend.business.persistence.MusicRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.MusicMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class MusicLinkTest extends BackendTestSupport {
    private static final String ROUTE = "/api/v1/music-links/resolve";
    @Autowired private MusicService service;
    @Autowired private AuthMapper users;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private MusicMapper mapper;
    private final List<String> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String user : userIds) {
            jdbc.update("DELETE FROM music_links WHERE owner_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    @ParameterizedTest
    @CsvSource({"music.163.com,NETEASE", "m.music.163.com,NETEASE", "163cn.tv,NETEASE",
            "y.qq.com,QQ", "c.y.qq.com,QQ", "kugou.com,KUGOU", "www.kugou.com,KUGOU",
            "spotify.com,SPOTIFY", "open.spotify.com,SPOTIFY", "spotify.link,SPOTIFY",
            "music.apple.com,APPLE_MUSIC"})
    void providers(String host, String provider) {
        Account owner = account();
        String url = "https://" + host + "/song?id=123";
        long before = Values.now();
        MusicLink result = resolve(owner, url);
        MusicRow row = mapper.get(result.id());
        assertEquals(provider, result.provider());
        assertEquals(url, result.sourceUrl());
        assertEquals(owner.user(), row.getOwnerId());
        assertEquals(provider, row.getProvider());
        assertTrue(row.getCreatedAt() >= before && row.getCreatedAt() <= Values.now());
        assertFallback(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"notmusic.163.com", "music.163.com.evil.test", "music163.com", "y.qq.com.evil.test",
            "notkugou.com", "kugou.com.evil.test", "fakespotify.com", "spotify.link.evil.test",
            "music.apple.com.evil.test", "example.test/path/music.163.com", "mus\u0456c.163.com", "sp\u043Etify.com"})
    void spoofedHost(String address) {
        MusicLink result = resolve(account(), "https://" + address);
        assertEquals("OTHER", result.provider());
        assertFallback(result);
    }

    @ParameterizedTest
    @MethodSource("shareCases")
    void shareText(String input, String expected) {
        assertEquals(expected, resolve(account(), input).sourceUrl());
    }

    static Stream<Arguments> shareCases() {
        String url = "https://music.163.com/song?id=123";
        return Stream.of(
                Arguments.of("分享歌曲：" + url + " 来自音乐平台", url),
                Arguments.of("歌曲\n" + url + "\n快来听听", url),
                Arguments.of("分享：\"" + url + "\"", url),
                Arguments.of("分享：“" + url + "”", url),
                Arguments.of("听歌（" + url + "）。", url),
                Arguments.of("听歌 (" + url + ").", url),
                Arguments.of("推荐【" + url + "】", url),
                Arguments.of("听歌《" + url + "》", url),
                Arguments.of("链接：" + url + ",", url),
                Arguments.of("\u00a0" + url + "\u3000", url),
                Arguments.of("https://example.test/song_(live)", "https://example.test/song_(live)"),
                Arguments.of("https://example.test/path.", "https://example.test/path."));
    }

    @ParameterizedTest
    @CsvSource({
            "HTTPS://MUSIC.163.COM:443/a/../song?id=123,https://music.163.com/song?id=123",
            "https://music.163.com./song,https://music.163.com/song",
            "https://music.163.com/#/song?id=123,https://music.163.com/#/song?id=123",
            "https://example.test/song?id=%2F&sign=a%2Bb%3D,https://example.test/song?id=%2F&sign=a%2Bb%3D",
            "https://example.test:8443/song,https://example.test:8443/song",
            "https://[2001:db8::1]:443/song,https://[2001:db8::1]/song",
            "https://example.test/歌曲?q=你好,https://example.test/%E6%AD%8C%E6%9B%B2?q=%E4%BD%A0%E5%A5%BD"})
    void normalizes(String input, String expected) {
        assertEquals(expected, resolve(account(), input).sourceUrl());
    }

    @Test
    void unicodeHost() {
        MusicLink result = resolve(account(), "https://音乐.example/歌曲");
        assertEquals("OTHER", result.provider());
        assertTrue(URI.create(result.sourceUrl()).getHost().startsWith("xn--"));
        assertTrue(result.sourceUrl().endsWith("/%E6%AD%8C%E6%9B%B2"));
        assertTrue(result.sourceUrl().chars().allMatch(c -> c < 128));
    }

    @Test
    void urlPunctuation() {
        Account owner = account();
        MusicLink host = resolve(owner, "HTTPS://MUSIC\u3002163\uFF0ECOM:443/song");
        assertEquals("https://music.163.com/song", host.sourceUrl());
        assertEquals("NETEASE", host.provider());
        MusicLink query = resolve(owner, "https://example.test/song?q=你好，朋友。");
        assertEquals("q=你好，朋友。", URI.create(query.sourceUrl()).getQuery());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "没有链接", "   ", "http://music.163.com/song", "ftp://example.test/song",
            "javascript:alert(1)", "//music.163.com/song", "https:///song", "https://",
            "https://user:password@music.163.com/song", "https://music.163.com@evil.test/song",
            "https://user%40name@music.163.com/song", "https://example.test:abc/song",
            "https://example.test:65536/song", "https://example.test:-1/song", "https://bad_host.test/song",
            "https://example.test/%ZZ", "https://[invalid]/song", "xhttps://music.163.com/song",
            "https://music.163.com/a https://spotify.com/b", "http://example.test/a https://music.163.com/b",
            "https://example.test/a https://example.test/a"})
    void invalidLink(String input) {
        Account owner = account();
        ApiException error = assertThrows(ApiException.class, () -> resolve(owner, input));
        assertEquals(400, error.status);
        assertEquals("MUSIC_LINK_INVALID", error.code);
        assertEquals(0, count(owner));
    }

    @Test
    void urlLimit() {
        Account owner = account();
        String prefix = "https://example.test/";
        String limit = prefix + "a".repeat(4096 - prefix.length());
        assertEquals(limit, resolve(owner, limit).sourceUrl());
        ApiException tooLong = assertThrows(ApiException.class, () -> resolve(owner, limit + "a"));
        assertEquals("MUSIC_LINK_INVALID", tooLong.code);
        assertEquals(1, count(owner));
    }

    @Test
    void asciiLimit() {
        Account owner = account();
        ApiException error = assertThrows(ApiException.class,
                () -> resolve(owner, "https://example.test/" + "歌".repeat(500)));
        assertEquals("MUSIC_LINK_INVALID", error.code);
        assertEquals("规范化后的地址过长", error.getMessage());
        assertEquals(0, count(owner));
    }

    @Test
    void hostLimit() {
        Account owner = account();
        String host = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63);
        assertThrows(ApiException.class, () -> resolve(owner, "https://" + host + "/song"));
        assertThrows(ApiException.class, () -> resolve(owner, "https://" + "a".repeat(64) + ".test/song"));
        assertEquals(0, count(owner));
    }

    @Test
    void fallback() {
        MusicLink result = resolve(account(), "https://127.0.0.1:1/unavailable");
        assertEquals("OTHER", result.provider());
        assertFallback(result);
    }

    @Test
    void ownerIsolation() {
        Account first = account();
        Account second = account();
        MusicLink a = resolve(first, "https://music.163.com/song?id=123");
        MusicLink b = resolve(second, a.sourceUrl());
        assertNotEquals(a.id(), b.id());
        assertEquals(first.user(), mapper.get(a.id()).getOwnerId());
        assertEquals(second.user(), mapper.get(b.id()).getOwnerId());
    }

    @Test
    void requiresUser() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.resolve(Viewer.guest(), new MusicLinkInput("https://example.test/song")));
        assertEquals(401, error.status);
    }

    @Test
    void rollback() {
        Account owner = account();
        MusicMapper actual = sql.getMapper(MusicMapper.class);
        doAnswer(call -> {
            actual.insert(call.getArgument(0));
            throw new DataAccessResourceFailureException("commit failed");
        }).when(mapper).insert(any(MusicRow.class));
        assertThrows(DataAccessResourceFailureException.class, () -> resolve(owner, "https://example.test/song"));
        assertEquals(0, count(owner));
    }

    @Test
    void logs() {
        Account owner = account();
        Logger logger = (Logger) LoggerFactory.getLogger(MusicService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
        try {
            MusicLink link = resolve(owner, "分享：https://music.163.com/song?token=private-token");
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.DEBUG, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("musicLinkId=" + link.id()));
            assertTrue(event.getFormattedMessage().contains("provider=NETEASE"));
            assertFalse(event.getFormattedMessage().contains("private-token"));
            assertFalse(event.getFormattedMessage().contains("https://"));
            assertFalse(event.getFormattedMessage().contains("分享"));
            assertThrows(ApiException.class, () -> resolve(owner, "无效分享"));
            assertEquals(1, appender.list.size());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
    }

    @Test
    void httpResponse() throws Exception {
        Account owner = account();
        MockHttpServletResponse response = request(owner, Map.of("input", "https://music.163.com/song?id=123"));
        Map<String, Object> envelope = json.readMap(response.getContentAsString());
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("music-test", envelope.get("requestId"));
        assertEquals(Set.of("data", "requestId"), envelope.keySet());
        Map<?, ?> data = (Map<?, ?>) envelope.get("data");
        assertEquals(Set.of("id", "provider", "sourceUrl", "title", "artist", "coverUrl", "metadataStatus", "playback"),
                data.keySet());
        assertEquals("NETEASE", data.get("provider"));
        assertEquals("EXTERNAL", ((Map<?, ?>) data.get("playback")).get("mode"));
        assertEquals(Set.of("mode", "embedUrl"), ((Map<?, ?>) data.get("playback")).keySet());
        assertNull(((Map<?, ?>) data.get("playback")).get("embedUrl"));
        assertEquals(owner.user(), mapper.get((String) data.get("id")).getOwnerId());
        assertEquals("private, no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"input\":null}", "{\"input\":\"\"}", "{\"input\":1}",
            "{\"input\":\"https://example.test/a\",\"ownerId\":\"other\"}"})
    void invalidBody(String body) throws Exception {
        Account owner = account();
        MockHttpServletResponse response = mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        error(response, 422, "VALIDATION_FAILED");
        assertEquals(0, count(owner));
    }

    @Test
    void inputLimit() throws Exception {
        Account owner = account();
        error(request(owner, Map.of("input", "a".repeat(4097))), 422, "VALIDATION_FAILED");
        error(request(owner, Map.of("input", "https://example.test/" + "歌".repeat(500))), 400, "MUSIC_LINK_INVALID");
        assertEquals(0, count(owner));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "media-cookie", "invalid", "revoked"})
    void httpAuth(String mode) throws Exception {
        Account owner = account();
        var request = post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                .content(json.write(Map.of("input", "https://example.test/song")));
        if ("media-cookie".equals(mode)) {
            String ticket = crypto.ticket("media-read", Values.map("user", owner.user(), "session", owner.session()), 60_000);
            request.cookie(new jakarta.servlet.http.Cookie("htw_media", ticket));
        } else if ("invalid".equals(mode)) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer invalid");
        } else if ("revoked".equals(mode)) {
            jdbc.update("UPDATE sessions SET revoked=TRUE WHERE id=?", owner.session());
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access());
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        error(response, 401, switch (mode) {
            case "invalid" -> "TOKEN_INVALID";
            case "revoked" -> "SESSION_REVOKED";
            default -> "AUTH_REQUIRED";
        });
        assertEquals(0, count(owner));
    }

    @Test
    void httpRollback() throws Exception {
        Account owner = account();
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(mapper).insert(any(MusicRow.class));
        error(request(owner, Map.of("input", "https://example.test/song")), 503, "SERVICE_UNAVAILABLE");
        assertEquals(0, count(owner));
    }

    private MusicLink resolve(Account owner, String input) {
        return service.resolve(new Viewer(owner.user(), owner.session()), new MusicLinkInput(input));
    }

    private void assertFallback(MusicLink link) {
        assertNull(link.title());
        assertNull(link.artist());
        assertNull(link.coverUrl());
        assertEquals("PARTIAL", link.metadataStatus());
        assertEquals(new MusicLink.Playback("EXTERNAL", null), link.playback());
    }

    private MockHttpServletResponse request(Account owner, Map<String, Object> input) throws Exception {
        return mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.access())
                .header("X-Request-ID", "music-test").contentType(MediaType.APPLICATION_JSON).content(json.write(input)))
                .andReturn().getResponse();
    }

    private void error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertEquals(code, json.readMap(response.getContentAsString()).get("code"));
    }

    private int count(Account owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM music_links WHERE owner_id=?", Integer.class, owner.user());
    }

    private Account account() {
        String user = Values.id();
        jdbc.update("""
                INSERT INTO users(id,email,username,password_hash,display_name,bio,email_verified,theme,motion,auto_play_media,created_at)
                VALUES(?,?,?,'private-password-hash','音乐用户','',TRUE,'SYSTEM','SYSTEM',TRUE,?)
                """, user, user + "@example.test", "music_" + user.substring(0, 8), Values.now());
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

    private record Account(String user, String session, String access) {}
}
