package com.silver.backend.business.user;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.Me;
import com.silver.backend.business.dto.ProfilePatch;
import com.silver.backend.business.dto.PublicUser;
import com.silver.backend.business.dto.Settings;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.business.system.PatchInput;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProfileService {
    @Autowired
    private Views  views;
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private AuthMapper authMapper;
    @Autowired
    private AssetMapper assetMapper;

    public PublicUser user(String id) {
        return views.user(id);
    }

    public Me me(Viewer viewer) {
        return views.me(viewer.requireUser());
    }

    public Me patch(Viewer viewer, PatchInput<ProfilePatch> input) {
        ProfilePatch values = input.value();
        List<String> resources = new ArrayList<>();
        resources.add("10:user:" + viewer.requireUser());
        if (values.avatarAssetId() != null) {
            resources.add("30:asset:" +  values.avatarAssetId());
        }
        return tx.write(
                resources,
                () -> {
                    UserRow user = authMapper.user(viewer.requireUser());
                    if (input.contains("displayName")) {
                        user.setDisplayName(
                                Requests.trimmed(values.displayName(), "displayName", true));
                    }
                    if (input.contains("bio")) {
                        user.setBio(values.bio().strip());
                    }
                    if (input.contains("avatarAssetId")) {
                        String id = values.avatarAssetId();
                        if (id != null) {
                            AssetRow asset = assetMapper.asset(id);
                            if (asset == null || !asset.getOwnerId().equals(user.getId())) {
                                throw new ApiException(422, "ASSET_OWNERSHIP_INVALID", "头像资产不属于当前用户");
                            }
                            if (!"AVATAR".equals(asset.getPurpose()) || !"IMAGE".equals(asset.getMediaType())) {
                                throw new ApiException(422, "ASSET_TYPE_MISMATCH", "头像用途不正确");
                            }
                            if (!"READY".equals(asset.getStatus())) {
                                throw new ApiException(422, "ASSET_NOT_READY", "头像尚未处理完成");
                            }
                        }
                        user.setAvatarAssetId(id);
                    }
                    authMapper.updateUser(user);
                    return views.me(user.getId());
                }
        );
    }

    public Settings settings(Viewer viewer) {
        return views.settings(authMapper.user(viewer.requireUser()));
    }
}
