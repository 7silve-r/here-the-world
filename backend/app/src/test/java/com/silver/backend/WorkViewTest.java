package com.silver.backend;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.Asset;
import com.silver.backend.business.dto.Cover;
import com.silver.backend.business.dto.MusicLink;
import com.silver.backend.business.dto.ViewerState;
import com.silver.backend.business.dto.WorkDetail;
import com.silver.backend.business.dto.WorkStats;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.MusicRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.MusicMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = "htw.public-api-base-url=https://api.example.test/api/v1")
class WorkViewTest extends BackendTestSupport {
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;
    private static final String CREATED_AT = "2023-11-14T22:13:20Z";

    @Autowired private Views views;
    @Autowired private WorkMapper works;
    @Autowired private AssetMapper assets;
    @Autowired private MusicMapper music;
    @Autowired private JdbcTemplate jdbc;
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
        for (String user : userIds) jdbc.update("DELETE FROM users WHERE id = ?", user);
    }

    @Test
    void textDetail() {
        String owner = user();
        WorkRow work = work(owner, "TEXT");
        String body = "🌍".repeat(300) + "结束";
        jdbc.update("UPDATE works SET body = ? WHERE id = ?", body, work.getId());
        work = works.get(work.getId());

        WorkDetail detail = views.detail(work, Viewer.guest());

        assertEquals(work.getId(), detail.id());
        assertEquals("TEXT", detail.kind());
        assertEquals("PUBLISHED", detail.status());
        assertEquals(7L, detail.version());
        assertEquals("作品标题", detail.title());
        assertEquals(body, detail.body());
        assertEquals("🌍".repeat(280), detail.excerpt());
        assertEquals(owner, detail.author().id());
        assertEquals("作者昵称", detail.author().displayName());
        assertEquals(1L, detail.author().publishedWorkCount());
        assertEquals(CREATED_AT, detail.createdAt());
        assertEquals("2023-11-14T22:13:21Z", detail.updatedAt());
        assertEquals("2023-11-14T22:13:22Z", detail.publishedAt());
        assertEquals(new WorkStats(0L, 0L, 0L), detail.stats());
        assertEquals(new ViewerState(false, false, false), detail.viewer());
        assertEquals(List.of(), detail.assets());
        assertNull(detail.cover());
        assertNull(detail.coverAsset());
        assertNull(detail.musicLink());
        assertNull(detail.failureCode());
    }

    @Test
    void mediaOrder() {
        String owner = user();
        WorkRow work = work(owner, "IMAGE");
        AssetRow second = asset(owner, "WORK_MEDIA", "IMAGE", "READY");
        AssetRow first = asset(owner, "WORK_MEDIA", "IMAGE", "READY");
        AssetRow cover = asset(owner, "WORK_COVER", "IMAGE", "READY");
        attach(work, second, 8);
        attach(work, first, 2);
        cover(work, cover);

        WorkDetail detail = views.detail(work, Viewer.guest());

        assertEquals(List.of(first.getId(), second.getId()), works.assets(work.getId()));
        assertEquals(List.of(first.getId(), second.getId()), detail.assets().stream().map(Asset::id).toList());
        assertEquals(cover.getId(), detail.coverAsset().id());
        assertEquals(publicUrl(cover, true), detail.cover().url());
        assertEquals(publicUrl(cover, false), detail.coverAsset().url());
        assertEquals(publicUrl(first, false), detail.assets().get(0).url());
        assertEquals(publicUrl(first, true), detail.assets().get(0).thumbnailUrl());
    }

    @Test
    void assetDetails() {
        String owner = user();
        AssetRow row = asset(owner, "WORK_MEDIA", "IMAGE", "READY");

        Asset result = views.asset(row, new Viewer(owner, "test-session"));

        assertEquals(row.getId(), result.id());
        assertEquals("WORK_MEDIA", result.purpose());
        assertEquals("IMAGE", result.mediaType());
        assertEquals("READY", result.status());
        assertEquals("image/jpeg", result.mimeType());
        assertEquals(4096L, result.sizeBytes());
        assertEquals(privateUrl(row, false), result.url());
        assertEquals(privateUrl(row, true), result.thumbnailUrl());
        assertEquals(1280L, result.width());
        assertEquals(720L, result.height());
        assertNull(result.durationMs());
        assertNull(result.failureCode());
        assertEquals(CREATED_AT, result.createdAt());
        Map<String, Object> body = json.readMap(json.write(result));
        assertEquals(Set.of("id", "purpose", "mediaType", "status", "mimeType", "sizeBytes",
                "url", "thumbnailUrl", "width", "height", "durationMs", "failureCode", "createdAt"), body.keySet());
    }

    @Test
    void audioDetails() {
        String owner = user();
        AssetRow row = asset(owner, "WORK_MEDIA", "AUDIO", "READY");

        Asset result = views.asset(row, new Viewer(owner, "test-session"));

        assertEquals("audio/flac", result.mimeType());
        assertEquals(privateUrl(row, false), result.url());
        assertNull(result.thumbnailUrl());
        assertNull(result.width());
        assertNull(result.height());
        assertEquals(12_345L, result.durationMs());
    }

    @Test
    void musicDetails() {
        MusicRow row = link(user(), "https://images.example.test/album.jpg");

        MusicLink result = views.music(music.get(row.getId()));

        assertEquals(row.getId(), result.id());
        assertEquals("NETEASE", result.provider());
        assertEquals("https://music.163.com/song?id=123", result.sourceUrl());
        assertEquals("歌曲名称", result.title());
        assertEquals("歌手名称", result.artist());
        assertEquals(row.getCoverUrl(), result.coverUrl());
        assertEquals("READY", result.metadataStatus());
        assertEquals(new MusicLink.Playback("EXTERNAL", null), result.playback());
    }

    @Test
    void noMusic() {
        assertNull(views.music(null));
        assertNull(music.get(Values.id()));
    }

    @Test
    void zeroStats() {
        WorkRow work = work(user(), "TEXT");

        assertEquals(new WorkStats(0L, 0L, 0L), views.stats(work.getId()));
    }

    @Test
    void realStats() {
        String owner = user();
        String first = user();
        String second = user();
        WorkRow work = work(owner, "TEXT");
        like(work, first);
        like(work, second);
        bookmark(work, first);
        comment(work, first);
        comment(work, second);
        WorkRow other = work(owner, "TEXT");
        like(other, first);
        bookmark(other, first);
        comment(other, first);

        assertEquals(new WorkStats(2L, 1L, 2L), views.stats(work.getId()));

        jdbc.update("DELETE FROM work_likes WHERE work_id = ? AND user_id = ?", work.getId(), first);
        jdbc.update("DELETE FROM comments WHERE work_id = ? AND user_id = ?", work.getId(), first);
        assertEquals(new WorkStats(1L, 1L, 1L), views.stats(work.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"guest", "owner", "liked", "bookmarked", "both", "neither"})
    void viewerState(String role) {
        String owner = user();
        WorkRow work = work(owner, "TEXT");
        String actor = "owner".equals(role) ? owner : user();
        boolean liked = "liked".equals(role) || "both".equals(role);
        boolean bookmarked = "bookmarked".equals(role) || "both".equals(role);
        if (liked) like(work, actor);
        if (bookmarked) bookmark(work, actor);
        Viewer viewer = "guest".equals(role) ? Viewer.guest()
                : new Viewer(new String(actor), "test-session");

        assertEquals(new ViewerState(liked, bookmarked, "owner".equals(role)), views.viewer(work, viewer));
    }

    @Test
    void firstImage() {
        String owner = user();
        WorkRow work = work(owner, "IMAGE");
        AssetRow later = asset(owner, "WORK_MEDIA", "IMAGE", "READY");
        AssetRow first = asset(owner, "WORK_MEDIA", "IMAGE", "READY");
        attach(work, later, 9);
        attach(work, first, 1);

        WorkDetail detail = views.detail(work, Viewer.guest());

        assertEquals(new Cover(publicUrl(first, true), 1280L, 720L, null), detail.cover());
        assertNull(detail.coverAsset(), "自动选出的封面不等于显式 coverAsset");
    }

    @Test
    void imageNoThumb() {
        String owner = user();
        WorkRow work = work(owner, "IMAGE");
        AssetRow image = asset(owner, "WORK_MEDIA", "IMAGE", "READY");
        jdbc.update("UPDATE assets SET thumbnail_key = NULL WHERE id = ?", image.getId());
        attach(work, image, 0);

        assertEquals(publicUrl(image, false), views.cover(work, Viewer.guest()).url());
    }

    @Test
    void videoThumbnail() {
        String owner = user();
        WorkRow work = work(owner, "VIDEO");
        AssetRow video = asset(owner, "WORK_MEDIA", "VIDEO", "READY");
        attach(work, video, 0);

        WorkDetail detail = views.detail(work, Viewer.guest());

        assertEquals(publicUrl(video, true), detail.cover().url());
        assertEquals(publicUrl(video, false), detail.assets().get(0).url());
        assertNotEquals(detail.cover().url(), detail.assets().get(0).url());
    }

    @Test
    void videoNoThumb() {
        String owner = user();
        WorkRow work = work(owner, "VIDEO");
        AssetRow video = asset(owner, "WORK_MEDIA", "VIDEO", "READY");
        jdbc.update("UPDATE assets SET thumbnail_key = NULL WHERE id = ?", video.getId());
        attach(work, video, 0);

        assertNull(views.cover(work, Viewer.guest()), "视频文件地址不能作为图片封面回退");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "UPLOADED", "PROCESSING", "FAILED"})
    void unreadyCover(String status) {
        String owner = user();
        WorkRow work = work(owner, "IMAGE");
        AssetRow image = asset(owner, "WORK_MEDIA", "IMAGE", status);
        attach(work, image, 0);

        WorkDetail detail = views.detail(work, Viewer.guest());

        assertNull(detail.cover());
        Asset result = detail.assets().get(0);
        assertEquals(status, result.status());
        assertNull(result.url());
        assertNull(result.thumbnailUrl());
        assertEquals(image.getFailureCode(), result.failureCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEXT", "AUDIO", "MUSIC_LINK"})
    void noCover(String kind) {
        String owner = user();
        WorkRow work = work(owner, kind);
        if ("AUDIO".equals(kind)) attach(work, asset(owner, "WORK_MEDIA", "AUDIO", "READY"), 0);
        if ("MUSIC_LINK".equals(kind)) music(work, link(owner, null));

        assertNull(views.cover(work, Viewer.guest()));
        assertNull(views.detail(work, Viewer.guest()).coverAsset());
    }

    @Test
    void musicCover() {
        String owner = user();
        WorkRow work = work(owner, "MUSIC_LINK");
        MusicRow link = link(owner, "https://images.example.test/album.jpg");
        music(work, link);

        WorkDetail detail = views.detail(work, Viewer.guest());

        assertEquals(new Cover(link.getCoverUrl(), null, null, null), detail.cover());
        assertEquals(link.getId(), detail.musicLink().id());
        AssetRow custom = asset(owner, "WORK_COVER", "IMAGE", "READY");
        cover(work, custom);
        assertEquals(publicUrl(custom, true), views.cover(work, Viewer.guest()).url());
    }

    private String user() {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'private-password-hash', '作者昵称', '作者简介',
                  TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, id + "@example.test", "view_" + id.replace("-", "").substring(0, 12), CREATED);
        userIds.add(id);
        return id;
    }

    private WorkRow work(String owner, String kind) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO works (id, owner_id, kind, status, version, title, body,
                  created_at, updated_at, published_at)
                VALUES (?, ?, ?, 'PUBLISHED', 7, '作品标题', '作品正文', ?, ?, ?)
                """, id, owner, kind, CREATED, CREATED + 1000, CREATED + 2000);
        workIds.add(id);
        return works.get(id);
    }

    private AssetRow asset(String owner, String purpose, String type, String status) {
        String id = Values.id();
        boolean audio = "AUDIO".equals(type);
        String mime = audio ? "audio/flac" : "VIDEO".equals(type) ? "video/mp4" : "image/jpeg";
        String playback = audio ? "processed/" + id + ".mp3"
                : "VIDEO".equals(type) ? "processed/" + id + ".mp4" : null;
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, playback_key, thumbnail_key, size_bytes, created_at,
                  width, height, duration_ms, failure_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 4096, ?, ?, ?, ?, ?)
                """, id, owner, purpose, type, status, mime, "original/" + id, playback,
                audio ? null : "thumbs/" + id + ".jpg", CREATED, audio ? null : 1280,
                audio ? null : 720, "IMAGE".equals(type) ? null : 12_345L,
                "FAILED".equals(status) ? "MEDIA_PROCESSING_FAILED" : null);
        assetIds.add(id);
        return assets.asset(id);
    }

    private MusicRow link(String owner, String cover) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO music_links (id, owner_id, provider, source_url, title, artist,
                  cover_url, metadata_status, created_at)
                VALUES (?, ?, 'NETEASE', 'https://music.163.com/song?id=123', '歌曲名称', '歌手名称', ?, ?, ?)
                """, id, owner, cover, cover == null ? "PARTIAL" : "READY", CREATED);
        musicIds.add(id);
        return music.get(id);
    }

    private void attach(WorkRow work, AssetRow asset, int position) {
        jdbc.update("INSERT INTO work_assets (work_id, asset_id, position) VALUES (?, ?, ?)",
                work.getId(), asset.getId(), position);
    }

    private void cover(WorkRow work, AssetRow asset) {
        jdbc.update("UPDATE works SET cover_asset_id = ? WHERE id = ?", asset.getId(), work.getId());
        work.setCoverAssetId(asset.getId());
    }

    private void music(WorkRow work, MusicRow link) {
        jdbc.update("UPDATE works SET music_link_id = ? WHERE id = ?", link.getId(), work.getId());
        work.setMusicLinkId(link.getId());
    }

    private void like(WorkRow work, String user) {
        jdbc.update("INSERT INTO work_likes (work_id, user_id, created_at) VALUES (?, ?, ?)",
                work.getId(), user, CREATED);
    }

    private void bookmark(WorkRow work, String user) {
        jdbc.update("INSERT INTO bookmarks (id, work_id, user_id, created_at) VALUES (?, ?, ?, ?)",
                Values.id(), work.getId(), user, CREATED);
    }

    private void comment(WorkRow work, String user) {
        jdbc.update("INSERT INTO comments (id, work_id, user_id, body, created_at) VALUES (?, ?, ?, '评论', ?)",
                Values.id(), work.getId(), user, CREATED);
    }

    private String publicUrl(AssetRow asset, boolean thumbnail) {
        return BASE + "/media/public/assets/" + asset.getId() + (thumbnail ? "/thumbnail" : "/file");
    }

    private String privateUrl(AssetRow asset, boolean thumbnail) {
        return BASE + "/media/private/assets/" + asset.getId() + (thumbnail ? "/thumbnail" : "/file");
    }
}
