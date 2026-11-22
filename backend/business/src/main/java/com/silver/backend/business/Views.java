package com.silver.backend.business;

import com.silver.backend.business.dto.Me;
import com.silver.backend.business.dto.PublicUser;
import com.silver.backend.business.dto.Settings;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class Views {
    @Autowired
    private AuthMapper authMapper;
    @Autowired
    private AssetMapper assetMapper;
    @Autowired
    private WorkMapper workMapper;
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
}
