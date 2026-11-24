package com.silver.backend.business;

import com.silver.backend.business.dto.*;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.MusicRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.*;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class Views {
    @Autowired
    private AuthMapper authMapper;
    @Autowired
    private AssetMapper assetMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private SocialMapper socialMapper;
    @Autowired
    private MusicMapper musicMapper;
    private final String baseUrl;

    public Views(@Value("${htw.public-api-base-url}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    private boolean publicAsset(String id) {
        return assetMapper.avatarReferences(id) > 0 || assetMapper.publicWorkReferences(id) > 0;
    }

    public String url(AssetRow a, boolean thumb, Viewer viewer) {
        String key = thumb ? a.getThumbnailKey() :
                (a.getPlaybackKey() == null) ? a.getStorageKey() : a.getPlaybackKey();
        if (!"READY".equals(a.getStatus()) || key == null) {
            return null;
        }
        boolean publicMedia = publicAsset(a.getId());
        if (!publicMedia && !Objects.equals(a.getOwnerId(), viewer.userId())) {
            return null;
        }
        return baseUrl
                + "/media/"
                + (publicMedia ? "public" : "private")
                + "/assets/"
                + a.getId()
                + (thumb ? "/thumbnail" : "/file");
    }

    public PublicUser user(String id) {
        UserRow user = authMapper.user(id);
        if (user == null) {
            throw new ApiException(404, "USER_NOT_FOUND", "用户不存在");
        }
        String avatar = null;
        if (user.getAvatarAssetId() != null) {
            AssetRow a = assetMapper.asset(user.getAvatarAssetId());
            if (a != null && "READY".equals(a.getStatus())) {
                avatar = url(a, false, Viewer.guest());
            }
        }
        return new PublicUser(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                avatar,
                workMapper.publishedCount(user.getId()),
                Values.time(user.getCreatedAt()));
    }

    public Me me(String id) {
        UserRow user = authMapper.user(id);
        PublicUser p = user(id);
        return new Me(
                p.id(),
                p.username(),
                p.displayName(),
                p.bio(),
                p.avatarUrl(),
                p.publishedWorkCount(),
                p.createdAt(),
                user.getEmail(),
                user.isEmailVerified());
    }

    public Settings settings(UserRow user) {
        return new Settings(user.getTheme(), user.getMotion(), user.isAutoPlayMedia());
    }

    public Asset asset(AssetRow a, Viewer viewer) {
        return new Asset(
                a.getId(),
                a.getPurpose(),
                a.getMediaType(),
                a.getStatus(),
                a.getMimeType(),
                a.getSizeBytes(),
                url(a, false, viewer),
                url(a, true, viewer),
                a.getWidth() == null ? null : a.getWidth().longValue(),
                a.getHeight() == null ? null : a.getHeight().longValue(),
                a.getDurationMs(),
                a.getFailureCode(),
                Values.time(a.getCreatedAt()));
    }

    public MusicLink music(MusicRow m) {
        return m == null
                ? null
                : new MusicLink(
                m.getId(),
                m.getProvider(),
                m.getSourceUrl(),
                m.getTitle(),
                m.getArtist(),
                m.getCoverUrl(),
                m.getMetadataStatus(),
                new MusicLink.Playback("EXTERNAL", null));
    }

    public WorkStats stats(String work) {
        return new WorkStats(socialMapper.likes(work), socialMapper.bookmarks(work), socialMapper.comments(work));
    }

    public ViewerState viewer(WorkRow w, Viewer v) {
        return new ViewerState(
                v.authenticated() && socialMapper.liked(w.getId(), v.userId()) > 0,
                v.authenticated() && socialMapper.bookmarked(w.getId(), v.userId()) > 0,
                Objects.equals(w.getOwnerId(), v.userId()));
    }

    private AssetRow coverAsset(WorkRow w) {
        if (w.getCoverAssetId() != null) {
            return assetMapper.asset(w.getCoverAssetId());
        }
        List<String> ids = workMapper.assets(w.getId());
        if (("IMAGE".equals(w.getKind()) || "VIDEO".equals(w.getKind())) && !ids.isEmpty()) {
            return assetMapper.asset(ids.get(0));
        }
        return null;
    }

    public Cover cover(WorkRow w, Viewer v) {
        AssetRow a = coverAsset(w);
        if (a != null) {
            String link = url(a, true, v);
            if (link == null && "IMAGE".equals(a.getMediaType())) {
                link = url(a, false, v);
            }
            if (link != null) {
                return new Cover(
                        link,
                        a.getWidth() == null ? null : a.getWidth().longValue(),
                        a.getHeight() == null ? null : a.getHeight().longValue(),
                        null);
            }
        }
        if (w.getMusicLinkId() != null) {
            MusicRow m = musicMapper.get(w.getMusicLinkId());
            if (m != null && m.getCoverUrl() != null) {
                return new Cover(m.getCoverUrl(), null, null, null);
            }
        }
        return null;
    }

    public WorkDetail detail(WorkRow w, Viewer v) {
        List<Asset> list =
                workMapper.assets(w.getId())
                        .stream()
                        .map(assetMapper::asset)
                        .filter(Objects::nonNull)
                        .map(a -> asset(a, v))
                        .toList();
        AssetRow cover = w.getCoverAssetId() == null ? null : assetMapper.asset(w.getCoverAssetId());
        return new WorkDetail(
                w.getId(),
                w.getKind(),
                w.getStatus(),
                w.getVersion(),
                w.getTitle(),
                Values.excerpt(w.getBody(), 280),
                user(w.getOwnerId()),
                cover(w, v),
                stats(w.getId()),
                viewer(w, v),
                Values.time(w.getCreatedAt()),
                Values.time(w.getPublishedAt()),
                w.getBody(),
                list,
                cover == null ? null : asset(cover, v),
                w.getMusicLinkId() == null ? null : music(musicMapper.get(w.getMusicLinkId())),
                Values.time(w.getUpdatedAt()),
                w.getFailureCode());
    }

    public WorkRow visible(String id, Viewer viewer) {
        WorkRow w = workMapper.get(id);
        if (w == null || (!"PUBLISHED".equals(w.getStatus()) && !Objects.equals(w.getOwnerId(), viewer.userId()))) {
            throw new ApiException(404, "WORK_NOT_FOUND", "作品不存在");
        }
        return w;
    }

    public WorkRow owned(String id, Viewer viewer) {
        WorkRow w = workMapper.get(id);
        String user = viewer.requiredUser();
        if (w == null || !w.getOwnerId().equals(user)) {
            throw new ApiException(404, "WORK_NOT_FOUND", "作品不存在");
        }
        return w;
    }

    public WorkCard workCard(WorkRow w, Viewer v) {
        return new WorkCard(
                w.getId(),
                w.getKind(),
                w.getStatus(),
                w.getVersion(),
                w.getTitle(),
                Values.excerpt(w.getBody(), 280),
                user(w.getOwnerId()),
                cover(w, v),
                stats(w.getId()),
                viewer(w, v),
                Values.time(w.getCreatedAt()),
                Values.time(w.getPublishedAt()));
    }
}
