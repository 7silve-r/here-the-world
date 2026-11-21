package com.silver.backend;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.Me;
import com.silver.backend.business.dto.PublicUser;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.ApiException;
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

@TestPropertySource(properties = "htw.public-api-base-url=https://api.example.test/api/v1///")
class ProfileViewsTest extends BackendTestSupport {
    private static final String BASE = "https://api.example.test/api/v1";
    private static final long CREATED = 1_700_000_000_000L;

    @Autowired private Views views;
    @Autowired private AuthMapper users;
    @Autowired private AssetMapper assets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonCodec json;

    private final List<String> userIds = new ArrayList<>();
    private final List<String> assetIds = new ArrayList<>();
    private final List<String> workIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        for (String work : workIds) {
            jdbc.update("DELETE FROM work_assets WHERE work_id = ?", work);
            jdbc.update("DELETE FROM works WHERE id = ?", work);
        }
        for (String asset : assetIds) {
            jdbc.update("DELETE FROM assets WHERE id = ?", asset);
        }
        for (String user : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void publicUser() {
        String owner = user();
        work(owner, "PUBLISHED", null);
        work(owner, "PUBLISHED", null);
        work(owner, "DRAFT", null);
        work(owner, "PROCESSING", null);
        work(user(), "PUBLISHED", null);

        PublicUser result = views.user(owner);

        assertEquals(owner, result.id());
        assertEquals(users.user(owner).getUsername(), result.username());
        assertEquals("测试用户", result.displayName());
        assertEquals("用户介绍", result.bio());
        assertNull(result.avatarUrl());
        assertEquals(2L, result.publishedWorkCount());
        assertEquals(Values.time(CREATED), result.createdAt());
        Map<String, Object> body = json.readMap(json.write(result));
        assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl",
                "publishedWorkCount", "createdAt"), body.keySet());
        assertFalse(body.containsKey("email"));
        assertFalse(body.containsKey("passwordHash"));
    }

    @Test
    void me() {
        String owner = user();
        AssetRow avatar = asset(owner, "READY");
        avatar(owner, avatar.getId());
        work(owner, "PUBLISHED", null);

        Me result = views.me(owner);

        assertEquals(owner, result.id());
        assertEquals(users.user(owner).getUsername(), result.username());
        assertEquals("测试用户", result.displayName());
        assertEquals("用户介绍", result.bio());
        assertEquals(publicUrl(avatar, false), result.avatarUrl());
        assertEquals(1L, result.publishedWorkCount());
        assertEquals(Values.time(CREATED), result.createdAt());
        assertEquals(users.user(owner).getEmail(), result.email());
        assertEquals(Boolean.TRUE, result.emailVerified());
        Map<String, Object> body = json.readMap(json.write(result));
        assertEquals(Set.of("id", "username", "displayName", "bio", "avatarUrl",
                "publishedWorkCount", "createdAt", "email", "emailVerified"), body.keySet());
    }

    @Test
    void missingUser() {
        ApiException error = assertThrows(ApiException.class, () -> views.user(Values.id()));
        assertEquals(404, error.status);
        assertEquals("USER_NOT_FOUND", error.code);
    }

    @Test
    void missingMe() {
        ApiException error = assertThrows(ApiException.class, () -> views.me(Values.id()));
        assertEquals(404, error.status);
        assertEquals("USER_NOT_FOUND", error.code);
    }

    @Test
    void readyAvatar() {
        String owner = user();
        AssetRow avatar = asset(owner, "READY");
        avatar(owner, avatar.getId());

        assertEquals(publicUrl(avatar, false), views.user(owner).avatarUrl());
        assertEquals(publicUrl(avatar, true), views.url(avatar, true, Viewer.guest()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PROCESSING", "FAILED"})
    void unreadyAvatar(String status) {
        String owner = user();
        AssetRow avatar = asset(owner, status);
        avatar(owner, avatar.getId());

        assertNull(views.user(owner).avatarUrl());
    }

    @Test
    void missingAvatar() {
        String owner = user();
        avatar(owner, Values.id());

        assertNull(views.user(owner).avatarUrl());
    }

    @Test
    void privateOwner() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");

        assertEquals(privateUrl(asset, false), views.url(asset, false, viewer(owner)));
        assertEquals(privateUrl(asset, true), views.url(asset, true, viewer(owner)));
    }

    @Test
    void privateGuest() {
        AssetRow asset = asset(user(), "READY");

        assertNull(views.url(asset, false, Viewer.guest()));
        assertNull(views.url(asset, true, Viewer.guest()));
    }

    @Test
    void otherViewer() {
        AssetRow asset = asset(user(), "READY");
        Viewer other = viewer(user());

        assertNull(views.url(asset, false, other));
        assertNull(views.url(asset, true, other));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PROCESSING", "FAILED"})
    void unreadyAsset(String status) {
        String owner = user();
        AssetRow asset = asset(owner, status);
        work(owner, "PUBLISHED", asset.getId());

        assertNull(views.url(asset, false, Viewer.guest()));
        assertNull(views.url(asset, true, viewer(owner)));
    }

    @Test
    void missingFile() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        asset.setStorageKey(null);
        asset.setPlaybackKey(null);

        assertNull(views.url(asset, false, viewer(owner)));
    }

    @Test
    void playbackOnly() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        asset.setStorageKey(null);
        asset.setPlaybackKey("playback/" + asset.getId() + ".mp4");

        assertEquals(privateUrl(asset, false), views.url(asset, false, viewer(owner)));
    }

    @Test
    void noThumbnail() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        asset.setThumbnailKey(null);

        assertNull(views.url(asset, true, viewer(owner)));
    }

    @Test
    void publishedCover() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        work(owner, "PUBLISHED", asset.getId());

        assertEquals(publicUrl(asset, false), views.url(asset, false, Viewer.guest()));
        assertEquals(publicUrl(asset, true), views.url(asset, true, viewer(user())));
    }

    @Test
    void publishedMedia() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        attach(work(owner, "PUBLISHED", null), asset.getId());

        assertEquals(publicUrl(asset, false), views.url(asset, false, Viewer.guest()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PROCESSING", "FAILED"})
    void unpublishedCover(String status) {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        work(owner, status, asset.getId());

        assertNull(views.url(asset, false, Viewer.guest()));
        assertEquals(privateUrl(asset, false), views.url(asset, false, viewer(owner)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PROCESSING", "FAILED"})
    void unpublishedMedia(String status) {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        attach(work(owner, status, null), asset.getId());

        assertNull(views.url(asset, false, Viewer.guest()));
        assertEquals(privateUrl(asset, false), views.url(asset, false, viewer(owner)));
    }

    @Test
    void losesPublicAccess() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        String work = work(owner, "PUBLISHED", asset.getId());
        assertEquals(publicUrl(asset, false), views.url(asset, false, Viewer.guest()));

        jdbc.update("UPDATE works SET status = 'DRAFT' WHERE id = ?", work);

        assertNull(views.url(asset, false, Viewer.guest()));
        assertEquals(privateUrl(asset, false), views.url(asset, false, viewer(owner)));
    }

    @Test
    void countsReferences() {
        String owner = user();
        AssetRow asset = asset(owner, "READY");
        String published = work(owner, "PUBLISHED", asset.getId());
        attach(published, asset.getId());
        work(owner, "DRAFT", asset.getId());

        assertEquals(1, assets.publicWorkReferences(asset.getId()));
    }

    private String user() {
        String id = Values.id();
        String username = "view_" + id.replace("-", "").substring(0, 12);
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, 'fixture', '测试用户', '用户介绍', TRUE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, id + "@example.test", username, CREATED);
        userIds.add(id);
        return id;
    }

    private AssetRow asset(String owner, String status) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO assets (id, owner_id, purpose, media_type, status, mime_type,
                  storage_key, thumbnail_key, size_bytes, created_at)
                VALUES (?, ?, 'WORK_MEDIA', 'IMAGE', ?, 'image/jpeg', ?, ?, 1024, ?)
                """, id, owner, status, "original/" + id + ".jpg", "thumbs/" + id + ".jpg", CREATED);
        assetIds.add(id);
        return assets.asset(id);
    }

    private String work(String owner, String status, String cover) {
        String id = Values.id();
        jdbc.update("""
                INSERT INTO works (id, owner_id, kind, status, version, title, body,
                  cover_asset_id, created_at, updated_at, published_at)
                VALUES (?, ?, 'IMAGE', ?, 1, '测试作品', '', ?, ?, ?, ?)
                """, id, owner, status, cover, CREATED, CREATED, "PUBLISHED".equals(status) ? CREATED : null);
        workIds.add(id);
        return id;
    }

    private void avatar(String user, String asset) {
        jdbc.update("UPDATE users SET avatar_asset_id = ? WHERE id = ?", asset, user);
    }

    private void attach(String work, String asset) {
        jdbc.update("INSERT INTO work_assets (work_id, asset_id, position) VALUES (?, ?, 0)", work, asset);
    }

    private Viewer viewer(String owner) {
        // 内容相同但引用不同的字符串，模拟从凭证和数据库分别读取用户 ID。
        return new Viewer(new String(owner), "test-session");
    }

    private String publicUrl(AssetRow asset, boolean thumb) {
        return BASE + "/media/public/assets/" + asset.getId() + (thumb ? "/thumbnail" : "/file");
    }

    private String privateUrl(AssetRow asset, boolean thumb) {
        return BASE + "/media/private/assets/" + asset.getId() + (thumb ? "/thumbnail" : "/file");
    }
}
